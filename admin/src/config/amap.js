// 高德地图配置
// key 与安全密钥通过 Vite 环境变量注入（本地配置在 admin/.env.local，已被 .gitignore 忽略）：
//   VITE_AMAP_KEY=你的Web端JS API key
//   VITE_AMAP_SECURITY_CODE=你的安全密钥
// 高德开放平台：https://console.amap.com/dev/key/app
export const AMAP_KEY = import.meta.env.VITE_AMAP_KEY || ''
export const AMAP_SECURITY_CODE = import.meta.env.VITE_AMAP_SECURITY_CODE || ''
