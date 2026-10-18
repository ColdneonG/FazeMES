import { onActivated, onBeforeUnmount, onDeactivated, onMounted, watch, type Ref } from 'vue'
import type * as Three from 'three'

const MAIN_COUNT = 220
const ACCENT_COUNT = 18
const SPREAD_X = 36
const SPREAD_Y = 22
const SPREAD_Z = 14
const FRAME_INTERVAL = 1000 / 30
const MAX_PIXEL_RATIO = 1.25

type ThreeModule = typeof import('three')

interface AmbientScene {
  renderer: Three.WebGLRenderer
  scene: Three.Scene
  camera: Three.PerspectiveCamera
  update: (delta: number, elapsed: number) => void
  dispose: () => void
}

export interface AeroAmbientEffectsController {
  pause: () => void
  resume: () => void
  dispose: () => void
}

function makeSprite(THREE: ThreeModule, color: Three.Color, alpha: number) {
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = 64
  const context = canvas.getContext('2d')
  if (!context) throw new Error('Canvas sprite unavailable')
  const gradient = context.createRadialGradient(32, 32, 0, 32, 32, 32)
  gradient.addColorStop(0, `rgba(${color.r * 255},${color.g * 255},${color.b * 255},${alpha})`)
  gradient.addColorStop(
    0.3,
    `rgba(${color.r * 255},${color.g * 255},${color.b * 255},${alpha * 0.6})`
  )
  gradient.addColorStop(1, 'rgba(0,0,0,0)')
  context.fillStyle = gradient
  context.fillRect(0, 0, 64, 64)
  return new THREE.CanvasTexture(canvas)
}

/** AeroForge LoginBackground's two particle layers, composited over the dashboard. */
function createAmbientScene(THREE: ThreeModule): AmbientScene | null {
  const canvas = document.createElement('canvas')
  let context: WebGL2RenderingContext | null = null
  let renderer: Three.WebGLRenderer | null = null
  let disposed = false
  const resources: Array<{ dispose: () => void }> = []
  const dispose = () => {
    if (disposed) return
    disposed = true
    resources.forEach((resource) => resource.dispose())
    resources.length = 0
    renderer?.dispose()
    if (renderer) renderer.forceContextLoss()
    else context?.getExtension('WEBGL_lose_context')?.loseContext()
    canvas.remove()
  }

  try {
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
    renderer.setClearColor(0x171a20, 0)
    canvas.className = 'aero-ambient-canvas'
    canvas.setAttribute('aria-hidden', 'true')
    canvas.setAttribute('role', 'presentation')

    const scene = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(60, 1, 0.5, 80)
    camera.position.set(0, 0, 20)
    camera.lookAt(0, 0, 0)
    const whiteTexture = makeSprite(THREE, new THREE.Color(1, 1, 1), 0.9)
    resources.push(whiteTexture)
    const blueTexture = makeSprite(THREE, new THREE.Color(0x3e / 255, 0x6a / 255, 0xe1 / 255), 1)
    resources.push(blueTexture)
    const positions = new Float32Array(MAIN_COUNT * 3)
    const colors = new Float32Array(MAIN_COUNT * 3)
    const sizes = new Float32Array(MAIN_COUNT)
    const velocities = new Float32Array(MAIN_COUNT)
    const accentColor = new THREE.Color(0x3e / 255, 0x6a / 255, 0xe1 / 255)
    for (let index = 0; index < MAIN_COUNT; index += 1) {
      const offset = index * 3
      positions[offset] = (Math.random() - 0.5) * SPREAD_X
      positions[offset + 1] = (Math.random() - 0.5) * SPREAD_Y
      positions[offset + 2] = (Math.random() - 0.5) * SPREAD_Z
      const isAccent = Math.random() < 0.15
      colors[offset] = isAccent ? accentColor.r : 1
      colors[offset + 1] = isAccent ? accentColor.g : 1
      colors[offset + 2] = isAccent ? accentColor.b : 1
      sizes[index] = isAccent ? 0.45 + Math.random() * 0.5 : 0.18 + Math.random() * 0.35
      velocities[index] = 0.15 + Math.random() * 0.55
    }
    const geometry = new THREE.BufferGeometry()
    resources.push(geometry)
    const positionAttribute = new THREE.BufferAttribute(positions, 3)
    geometry.setAttribute('position', positionAttribute)
    geometry.setAttribute('color', new THREE.BufferAttribute(colors, 3))
    geometry.setAttribute('size', new THREE.BufferAttribute(sizes, 1))
    const material = new THREE.PointsMaterial({
      size: 0.7,
      map: whiteTexture,
      vertexColors: true,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
      depthTest: false,
      transparent: true,
      opacity: 0.72
    })
    resources.push(material)
    const mainPoints = new THREE.Points(geometry, material)
    mainPoints.name = 'aero-ambient-main'
    scene.add(mainPoints)

    const accentPositions = new Float32Array(ACCENT_COUNT * 3)
    const accentSizes = new Float32Array(ACCENT_COUNT)
    const accentVelocities = new Float32Array(ACCENT_COUNT)
    for (let index = 0; index < ACCENT_COUNT; index += 1) {
      const offset = index * 3
      accentPositions[offset] = (Math.random() - 0.5) * SPREAD_X
      accentPositions[offset + 1] = (Math.random() - 0.5) * SPREAD_Y
      accentPositions[offset + 2] = (Math.random() - 0.5) * SPREAD_Z
      accentSizes[index] = 0.9 + Math.random() * 0.8
      accentVelocities[index] = 0.08 + Math.random() * 0.3
    }
    const accentGeometry = new THREE.BufferGeometry()
    resources.push(accentGeometry)
    const accentPositionAttribute = new THREE.BufferAttribute(accentPositions, 3)
    accentGeometry.setAttribute('position', accentPositionAttribute)
    accentGeometry.setAttribute('size', new THREE.BufferAttribute(accentSizes, 1))
    const accentMaterial = new THREE.PointsMaterial({
      size: 1.1,
      map: blueTexture,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
      depthTest: false,
      transparent: true,
      opacity: 0.55
    })
    resources.push(accentMaterial)
    const accentPoints = new THREE.Points(accentGeometry, accentMaterial)
    accentPoints.name = 'aero-ambient-accent'
    scene.add(accentPoints)

    const update = (delta: number, elapsed: number) => {
      camera.position.x = Math.sin(elapsed * 0.12) * 1.5
      camera.position.y = Math.cos(elapsed * 0.08) * 0.8
      camera.lookAt(0, 2, 0)
      // The upstream horizontal drift is per frame at 60 Hz; preserve its speed at 30 Hz.
      const driftScale = delta * 60
      for (let index = 0; index < MAIN_COUNT; index += 1) {
        const offset = index * 3
        positions[offset + 1] += velocities[index] * delta
        positions[offset] += Math.sin(elapsed * 0.4 + index * 0.3) * 0.015 * driftScale
        if (positions[offset + 1] > SPREAD_Y / 2 + 2) {
          positions[offset + 1] = -SPREAD_Y / 2 - 2
          positions[offset] = (Math.random() - 0.5) * SPREAD_X
          positions[offset + 2] = (Math.random() - 0.5) * SPREAD_Z
        }
      }
      positionAttribute.needsUpdate = true
      for (let index = 0; index < ACCENT_COUNT; index += 1) {
        const offset = index * 3
        accentPositions[offset + 1] += accentVelocities[index] * delta
        accentPositions[offset] += Math.sin(elapsed * 0.3 + index * 0.7) * 0.02 * driftScale
        if (accentPositions[offset + 1] > SPREAD_Y / 2 + 2) {
          accentPositions[offset + 1] = -SPREAD_Y / 2 - 2
          accentPositions[offset] = (Math.random() - 0.5) * SPREAD_X
          accentPositions[offset + 2] = (Math.random() - 0.5) * SPREAD_Z
        }
      }
      accentPositionAttribute.needsUpdate = true
    }
    return { renderer, scene, camera, update, dispose }
  } catch {
    dispose()
    return null
  }
}

/** One ambient context per dashboard, independently owned from its hover-card context. */
export function createAeroAmbientEffects(root: HTMLElement): AeroAmbientEffectsController {
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
  const originalStatus = root.getAttribute('data-aero-ambient')
  let disposed = false
  let enabled = true
  let visible = typeof IntersectionObserver === 'undefined'
  let unavailable = false
  let scene: AmbientScene | null = null
  let loading: Promise<void> | null = null
  let frame = 0
  let previousRenderTime: number | null = null
  let elapsed = 0
  let width = 0
  let height = 0
  let pixelRatio = 0

  const canRun = () =>
    !disposed &&
    !unavailable &&
    enabled &&
    visible &&
    !document.hidden &&
    !reducedMotion.matches &&
    root.isConnected &&
    root.clientWidth > 0 &&
    root.clientHeight > 0

  const stop = () => {
    if (frame) window.cancelAnimationFrame(frame)
    frame = 0
    previousRenderTime = null
    if (scene) scene.renderer.domElement.style.visibility = 'hidden'
  }

  const onContextLost = (event: Event) => {
    event.preventDefault()
    failWebgl()
  }

  const releaseScene = () => {
    stop()
    if (!scene) return
    scene.renderer.domElement.removeEventListener('webglcontextlost', onContextLost)
    scene.dispose()
    scene = null
    width = 0
    height = 0
    pixelRatio = 0
  }

  const failWebgl = () => {
    unavailable = true
    releaseScene()
    if (!disposed) root.setAttribute('data-aero-ambient', 'unavailable')
  }

  const resize = () => {
    if (!scene || !canRun()) return
    const nextWidth = root.clientWidth
    const nextHeight = root.clientHeight
    const nextPixelRatio = Math.min(window.devicePixelRatio || 1, MAX_PIXEL_RATIO)
    if (nextPixelRatio !== pixelRatio) {
      pixelRatio = nextPixelRatio
      scene.renderer.setPixelRatio(pixelRatio)
    }
    if (nextWidth !== width || nextHeight !== height) {
      width = nextWidth
      height = nextHeight
      scene.camera.aspect = width / height
      scene.camera.updateProjectionMatrix()
      scene.renderer.setSize(width, height, false)
    }
  }

  const animate = (time: number) => {
    frame = 0
    if (!canRun() || !scene) {
      stop()
      if (!disposed && !unavailable) {
        root.setAttribute('data-aero-ambient', reducedMotion.matches ? 'reduced' : 'paused')
      }
      return
    }
    const duration = previousRenderTime === null ? FRAME_INTERVAL : time - previousRenderTime
    if (duration >= FRAME_INTERVAL) {
      previousRenderTime = time
      const delta = Math.min(duration / 1000, 0.1)
      elapsed += delta
      try {
        scene.update(delta, elapsed)
        scene.renderer.render(scene.scene, scene.camera)
      } catch {
        failWebgl()
        return
      }
    }
    if (canRun() && scene) frame = window.requestAnimationFrame(animate)
  }

  const start = () => {
    if (!scene || !canRun()) return
    try {
      resize()
      scene.renderer.domElement.style.visibility = 'visible'
      root.setAttribute('data-aero-ambient', 'running')
      if (!frame) frame = window.requestAnimationFrame(animate)
    } catch {
      failWebgl()
    }
  }

  const ensureScene = () => {
    if (!canRun()) return
    if (scene) {
      start()
      return
    }
    if (loading) return
    root.setAttribute('data-aero-ambient', 'loading')
    loading = import('three')
      .then((THREE) => {
        if (!canRun()) return
        scene = createAmbientScene(THREE)
        if (!scene) {
          failWebgl()
          return
        }
        scene.renderer.domElement.addEventListener('webglcontextlost', onContextLost)
        root.appendChild(scene.renderer.domElement)
        start()
      })
      .catch(() => {
        if (!disposed) failWebgl()
      })
      .finally(() => {
        loading = null
      })
  }

  const sync = () => {
    if (disposed || unavailable) return
    if (!canRun()) {
      stop()
      root.setAttribute('data-aero-ambient', reducedMotion.matches ? 'reduced' : 'paused')
      if (reducedMotion.matches) releaseScene()
      return
    }
    ensureScene()
  }

  const resizeObserver = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(sync)
  resizeObserver?.observe(root)
  const intersectionObserver =
    typeof IntersectionObserver === 'undefined'
      ? null
      : new IntersectionObserver(([entry]) => {
          visible = entry?.isIntersecting ?? false
          sync()
        })
  intersectionObserver?.observe(root)
  reducedMotion.addEventListener('change', sync)
  document.addEventListener('visibilitychange', sync)
  document.addEventListener('fullscreenchange', sync)
  window.addEventListener('resize', sync, { passive: true })
  window.visualViewport?.addEventListener('resize', sync, { passive: true })
  sync()

  return {
    pause: () => {
      enabled = false
      sync()
    },
    resume: () => {
      enabled = true
      sync()
    },
    dispose: () => {
      if (disposed) return
      disposed = true
      releaseScene()
      resizeObserver?.disconnect()
      intersectionObserver?.disconnect()
      reducedMotion.removeEventListener('change', sync)
      document.removeEventListener('visibilitychange', sync)
      document.removeEventListener('fullscreenchange', sync)
      window.removeEventListener('resize', sync)
      window.visualViewport?.removeEventListener('resize', sync)
      if (originalStatus === null) root.removeAttribute('data-aero-ambient')
      else root.setAttribute('data-aero-ambient', originalStatus)
    }
  }
}

/** Call once from setup; Vue ref changes, KeepAlive and unmount own all resources. */
export function useAeroAmbientEffects(root: Readonly<Ref<HTMLElement | null | undefined>>) {
  let controller: AeroAmbientEffectsController | undefined
  let stopWatching: (() => void) | undefined
  let enabled = true
  onMounted(() => {
    stopWatching = watch(
      root,
      (element, _previous, onCleanup) => {
        if (!element) return
        controller = createAeroAmbientEffects(element)
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
