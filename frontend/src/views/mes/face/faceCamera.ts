export type CameraState = { ready: boolean; loading: boolean; message: string }
export function createFaceCamera(
  getVideo: () => HTMLVideoElement | undefined,
  update: (state: CameraState) => void
) {
  let stream: MediaStream | undefined
  let generation = 0
  let disposed = false
  const release = (value: MediaStream | undefined) =>
    value?.getTracks().forEach((track) => track.stop())
  const stop = () => {
    generation++
    release(stream)
    stream = undefined
    const video = getVideo()
    if (video) video.srcObject = null
    if (!disposed) update({ ready: false, loading: false, message: '摄像头已关闭' })
  }
  return {
    async start() {
      if (disposed) return
      stop()
      const current = ++generation
      if (!navigator.mediaDevices?.getUserMedia) {
        update({
          ready: false,
          loading: false,
          message: '当前环境不支持摄像头，请使用 HTTPS 或 localhost。'
        })
        return
      }
      update({ ready: false, loading: true, message: '请在浏览器中允许本次摄像头使用' })
      let next: MediaStream | undefined
      try {
        next = await navigator.mediaDevices.getUserMedia({
          audio: false,
          video: { facingMode: 'user', width: { ideal: 640 }, height: { ideal: 480 } }
        })
        const video = getVideo()
        if (disposed || current !== generation || !video) {
          release(next)
          return
        }
        stream = next
        video.srcObject = next
        await video.play()
        if (disposed || current !== generation) {
          release(next)
          return
        }
        update({ ready: true, loading: false, message: '请保持单人正脸，光线清晰' })
      } catch {
        release(next)
        if (disposed || current !== generation) return
        stream = undefined
        const video = getVideo()
        if (video) video.srcObject = null
        update({ ready: false, loading: false, message: '无法打开摄像头，请检查设备及浏览器权限' })
      }
    },
    capture() {
      const video = getVideo()
      if (!stream || !video?.videoWidth || !video.videoHeight || disposed)
        throw new Error('摄像头画面尚未就绪')
      const canvas = document.createElement('canvas')
      const scale = Math.min(1, 640 / video.videoWidth, 480 / video.videoHeight)
      canvas.width = Math.round(video.videoWidth * scale)
      canvas.height = Math.round(video.videoHeight * scale)
      const context = canvas.getContext('2d')
      if (!context) throw new Error('无法读取摄像头画面')
      context.drawImage(video, 0, 0, canvas.width, canvas.height)
      return canvas.toDataURL('image/jpeg', 0.88)
    },
    stop,
    dispose() {
      disposed = true
      stop()
    }
  }
}
