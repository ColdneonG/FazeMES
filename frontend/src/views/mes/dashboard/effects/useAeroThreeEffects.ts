import { onActivated, onBeforeUnmount, onDeactivated, onMounted, watch, type Ref } from 'vue'
import type * as Three from 'three'

const PANEL_SELECTOR = '.screen-panel, .kpi-card, [data-aero-panel]'
const ACTIVE_CLASS = 'three-hover-active'
const ROOT_CLASS = 'aero-effects-enabled'
const PARTICLE_COUNT = 46

type ThreeModule = typeof import('three')

interface ThreeScene {
  renderer: Three.WebGLRenderer
  camera: Three.PerspectiveCamera
  scene: Three.Scene
  particles: Three.Points<Three.BufferGeometry, Three.PointsMaterial>
  rings: Three.Group
  dispose: () => void
}

export interface AeroThreeEffectsController {
  pause: () => void
  resume: () => void
  dispose: () => void
}

/**
 * The original AeroForge control-center scene: 46 colored points and two rings.
 * Resources belong to one dashboard, and its single canvas follows the hovered panel.
 */
function createThreeScene(THREE: ThreeModule): ThreeScene | null {
  const canvas = document.createElement('canvas')
  let context: WebGL2RenderingContext | null = null
  let renderer: Three.WebGLRenderer | null = null
  const resources: Array<{ dispose: () => void }> = []
  const dispose = () => {
    resources.forEach((resource) => resource.dispose())
    resources.length = 0
    renderer?.dispose()
    if (renderer) renderer.forceContextLoss()
    else context?.getExtension('WEBGL_lose_context')?.loseContext()
    canvas.remove()
  }

  try {
    // Probe without invoking Three's renderer error path when WebGL is disabled.
    context = canvas.getContext('webgl2', {
      alpha: true,
      antialias: true,
      powerPreference: 'low-power'
    })
    if (!context) return null
    renderer = new THREE.WebGLRenderer({
      canvas,
      context,
      alpha: true,
      antialias: true,
      powerPreference: 'low-power'
    })
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5))
    renderer.setClearColor(0x000000, 0)
    canvas.className = 'aero-three-canvas'
    canvas.setAttribute('aria-hidden', 'true')
    canvas.setAttribute('role', 'presentation')

    const scene = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(42, 1, 0.1, 20)
    camera.position.z = 5
    const positions = new Float32Array(PARTICLE_COUNT * 3)
    const colors = new Float32Array(PARTICLE_COUNT * 3)
    for (let index = 0; index < PARTICLE_COUNT; index += 1) {
      const offset = index * 3
      positions[offset] = (Math.random() - 0.5) * 4.8
      positions[offset + 1] = (Math.random() - 0.5) * 3.2
      positions[offset + 2] = (Math.random() - 0.5) * 1.4
      colors[offset] = 0.12 + Math.random() * 0.25
      colors[offset + 1] = 0.7 + Math.random() * 0.3
      colors[offset + 2] = 1
    }
    const geometry = new THREE.BufferGeometry()
    resources.push(geometry)
    geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3))
    geometry.setAttribute('color', new THREE.BufferAttribute(colors, 3))
    const material = new THREE.PointsMaterial({
      size: 0.065,
      vertexColors: true,
      transparent: true,
      opacity: 0.86,
      blending: THREE.AdditiveBlending,
      depthWrite: false
    })
    resources.push(material)
    const particles = new THREE.Points(geometry, material)
    particles.name = 'aero-hover-particles'
    const rings = new THREE.Group()
    rings.name = 'aero-hover-rings'
    const ringDefinitions = [
      { inner: 1.28, outer: 1.31, segments: 64, color: 0x30e9ff, opacity: 0.35 },
      { inner: 0.78, outer: 0.8, segments: 48, color: 0xa777ff, opacity: 0.25 }
    ]
    for (const definition of ringDefinitions) {
      const ringGeometry = new THREE.RingGeometry(
        definition.inner,
        definition.outer,
        definition.segments
      )
      resources.push(ringGeometry)
      const ringMaterial = new THREE.MeshBasicMaterial({
        color: definition.color,
        transparent: true,
        opacity: definition.opacity,
        side: THREE.DoubleSide
      })
      resources.push(ringMaterial)
      rings.add(new THREE.Mesh(ringGeometry, ringMaterial))
    }
    scene.add(particles, rings)
    return { renderer, camera, scene, particles, rings, dispose }
  } catch {
    dispose()
    return null
  }
}

/** Standalone lifecycle for the dashboard; exported for focused browser verification. */
export function createAeroThreeEffects(
  root: HTMLElement,
  panelSelector = PANEL_SELECTOR
): AeroThreeEffectsController {
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
  const hoverPointer = window.matchMedia('(any-hover: hover) and (any-pointer: fine)')
  const originalMotion = root.getAttribute('data-aero-motion')
  const originalWebgl = root.getAttribute('data-aero-webgl')
  const hadRootClass = root.classList.contains(ROOT_CLASS)
  root.classList.add(ROOT_CLASS)

  let disposed = false
  let enabled = true
  let active: HTMLElement | null = null
  let scene: ThreeScene | null = null
  let loading: Promise<void> | null = null
  let unavailable = false
  let frame = 0
  let previousTime = 0
  let elapsedTime = 0
  let tiltX = 0
  let tiltY = 0
  let renderWidth = 0
  let renderHeight = 0
  let pixelRatio = 0

  const canAnimate = () =>
    !disposed && enabled && !document.hidden && !reducedMotion.matches && hoverPointer.matches

  const stopFrame = () => {
    if (frame) window.cancelAnimationFrame(frame)
    frame = 0
    previousTime = 0
  }

  const onContextLost = (event: Event) => {
    event.preventDefault()
    disableWebgl()
  }

  const releaseScene = () => {
    stopFrame()
    if (!scene) return
    scene.renderer.domElement.removeEventListener('webglcontextlost', onContextLost)
    scene.dispose()
    scene = null
    renderWidth = 0
    renderHeight = 0
    pixelRatio = 0
  }

  const disableWebgl = () => {
    unavailable = true
    releaseScene()
    root.setAttribute('data-aero-webgl', 'unavailable')
  }

  const clearActive = () => {
    stopFrame()
    if (active) {
      resizeObserver?.unobserve(active)
      active.classList.remove(ACTIVE_CLASS)
      active.style.removeProperty('--three-rotate-x')
      active.style.removeProperty('--three-rotate-y')
    }
    active = null
    tiltX = 0
    tiltY = 0
    scene?.renderer.domElement.remove()
  }

  const resizeScene = () => {
    if (!scene || !active || !canAnimate()) return false
    // clientWidth is unaffected by the card's CSS perspective/scale transform.
    const width = active.clientWidth
    const height = active.clientHeight
    if (width <= 0 || height <= 0) {
      scene.renderer.domElement.hidden = true
      stopFrame()
      return false
    }
    scene.renderer.domElement.hidden = false
    const nextPixelRatio = Math.min(window.devicePixelRatio || 1, 1.5)
    if (nextPixelRatio !== pixelRatio) {
      pixelRatio = nextPixelRatio
      scene.renderer.setPixelRatio(pixelRatio)
    }
    if (width !== renderWidth || height !== renderHeight) {
      renderWidth = width
      renderHeight = height
      scene.camera.aspect = width / height
      scene.camera.updateProjectionMatrix()
      scene.renderer.setSize(width, height, false)
    }
    return true
  }

  const animate = (time: number) => {
    frame = 0
    if (!active || !canAnimate() || !scene) return
    if (!active.isConnected || !root.contains(active)) {
      clearActive()
      return
    }
    const delta = previousTime ? Math.min(time - previousTime, 64) : 1000 / 60
    previousTime = time
    elapsedTime += delta
    scene.particles.rotation.z = elapsedTime * 0.00024
    scene.particles.rotation.x +=
      (tiltY * 0.16 - scene.particles.rotation.x) * (1 - Math.pow(0.955, delta / (1000 / 60)))
    scene.rings.rotation.z = -elapsedTime * 0.00032
    const ringSmoothing = 1 - Math.pow(0.96, delta / (1000 / 60))
    scene.rings.rotation.x += (tiltY * 0.24 - scene.rings.rotation.x) * ringSmoothing
    scene.rings.rotation.y += (tiltX * 0.24 - scene.rings.rotation.y) * ringSmoothing
    try {
      scene.renderer.render(scene.scene, scene.camera)
      if (scene && active && canAnimate()) frame = window.requestAnimationFrame(animate)
    } catch {
      // A graphics failure must leave the card and all business controls usable.
      disableWebgl()
    }
  }

  const startFrame = () => {
    try {
      if (active && canAnimate() && resizeScene() && !frame) {
        frame = window.requestAnimationFrame(animate)
      }
    } catch {
      disableWebgl()
    }
  }

  const attachCanvas = () => {
    if (!scene || !active || !canAnimate()) return
    active.appendChild(scene.renderer.domElement)
    startFrame()
  }

  const ensureScene = () => {
    if (scene) {
      attachCanvas()
      return
    }
    if (loading || unavailable || !canAnimate()) return
    root.setAttribute('data-aero-webgl', 'pending')
    loading = import('three')
      .then((THREE) => {
        if (!active || !active.isConnected || !root.contains(active) || !canAnimate()) return
        scene = createThreeScene(THREE)
        if (!scene) {
          disableWebgl()
          return
        }
        scene.renderer.domElement.addEventListener('webglcontextlost', onContextLost)
        root.setAttribute('data-aero-webgl', 'ready')
        attachCanvas()
      })
      .catch(() => {
        if (!disposed) disableWebgl()
      })
      .finally(() => {
        loading = null
      })
  }

  const resizeObserver =
    typeof ResizeObserver === 'undefined'
      ? null
      : new ResizeObserver(() => {
          if (active) startFrame()
        })

  const findCard = (target: EventTarget | null) => {
    if (!(target instanceof Element)) return null
    const card = target.closest<HTMLElement>(panelSelector)
    return card && root.contains(card) ? card : null
  }

  const onPointer = (event: PointerEvent) => {
    // Dragging the workshop map, selecting text and pressing controls keep a stable surface.
    if (event.pointerType !== 'mouse' || event.buttons !== 0 || !canAnimate()) return
    const card = findCard(event.target)
    if (!card) return
    if (active !== card) {
      clearActive()
      active = card
      active.classList.add(ACTIVE_CLASS)
      resizeObserver?.observe(card)
      ensureScene()
    }
    const bounds = card.getBoundingClientRect()
    if (!bounds.width || !bounds.height) return
    tiltX = Math.max(-1, Math.min(1, ((event.clientX - bounds.left) / bounds.width - 0.5) * 2))
    tiltY = Math.max(-1, Math.min(1, ((event.clientY - bounds.top) / bounds.height - 0.5) * 2))
    card.style.setProperty('--three-rotate-x', `${-tiltY * 3.5}deg`)
    card.style.setProperty('--three-rotate-y', `${tiltX * 4.5}deg`)
  }

  const onPointerOut = (event: PointerEvent) => {
    if (active && !(event.relatedTarget instanceof Node && active.contains(event.relatedTarget))) {
      clearActive()
    }
  }

  const syncMotion = () => {
    if (disposed) return
    root.setAttribute(
      'data-aero-motion',
      reducedMotion.matches ? 'reduced' : enabled && !document.hidden ? 'running' : 'paused'
    )
    if (!canAnimate()) clearActive()
    // Free the graphics context when the user's motion/input preference disables it.
    if (reducedMotion.matches || !hoverPointer.matches) releaseScene()
  }

  const onResize = () => {
    if (!active || !canAnimate()) return
    try {
      startFrame()
    } catch {
      disableWebgl()
    }
  }

  root.addEventListener('pointerover', onPointer)
  root.addEventListener('pointermove', onPointer)
  root.addEventListener('pointerout', onPointerOut)
  root.addEventListener('pointerdown', clearActive)
  root.addEventListener('pointerleave', clearActive)
  root.addEventListener('pointercancel', clearActive)
  root.addEventListener('scroll', clearActive, { passive: true })
  window.addEventListener('blur', clearActive)
  window.addEventListener('resize', onResize, { passive: true })
  window.visualViewport?.addEventListener('resize', onResize, { passive: true })
  document.addEventListener('fullscreenchange', onResize)
  document.addEventListener('visibilitychange', syncMotion)
  reducedMotion.addEventListener('change', syncMotion)
  hoverPointer.addEventListener('change', syncMotion)
  syncMotion()

  const restoreAttribute = (name: string, original: string | null) => {
    if (original === null) root.removeAttribute(name)
    else root.setAttribute(name, original)
  }

  return {
    pause: () => {
      enabled = false
      syncMotion()
    },
    resume: () => {
      enabled = true
      syncMotion()
    },
    dispose: () => {
      if (disposed) return
      disposed = true
      clearActive()
      resizeObserver?.disconnect()
      releaseScene()
      root.removeEventListener('pointerover', onPointer)
      root.removeEventListener('pointermove', onPointer)
      root.removeEventListener('pointerout', onPointerOut)
      root.removeEventListener('pointerdown', clearActive)
      root.removeEventListener('pointerleave', clearActive)
      root.removeEventListener('pointercancel', clearActive)
      root.removeEventListener('scroll', clearActive)
      window.removeEventListener('blur', clearActive)
      window.removeEventListener('resize', onResize)
      window.visualViewport?.removeEventListener('resize', onResize)
      document.removeEventListener('fullscreenchange', onResize)
      document.removeEventListener('visibilitychange', syncMotion)
      reducedMotion.removeEventListener('change', syncMotion)
      hoverPointer.removeEventListener('change', syncMotion)
      if (!hadRootClass) root.classList.remove(ROOT_CLASS)
      restoreAttribute('data-aero-motion', originalMotion)
      restoreAttribute('data-aero-webgl', originalWebgl)
    }
  }
}

/** Call once from setup; late data/v-if cards work through delegated pointer events. */
export function useAeroThreeEffects(
  root: Readonly<Ref<HTMLElement | null | undefined>>,
  panelSelector = PANEL_SELECTOR
) {
  let controller: AeroThreeEffectsController | undefined
  let stopWatching: (() => void) | undefined
  let enabled = true
  onMounted(() => {
    stopWatching = watch(
      root,
      (element, _previous, onCleanup) => {
        if (!element) return
        controller = createAeroThreeEffects(element, panelSelector)
        if (!enabled) controller.pause()
        onCleanup(() => {
          controller?.dispose()
          controller = undefined
        })
      },
      { immediate: true, flush: 'post' }
    )
  })
  onActivated(() => {
    enabled = true
    controller?.resume()
  })
  onDeactivated(() => {
    enabled = false
    controller?.pause()
  })
  onBeforeUnmount(() => {
    stopWatching?.()
    controller?.dispose()
  })
}
