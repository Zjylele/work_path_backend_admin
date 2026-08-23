import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import router from '@/router'

const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 请求拦截：注入 token
request.interceptors.request.use((config) => {
  const userStore = useUserStore()
  if (userStore.token) {
    config.headers.Authorization = `Bearer ${userStore.token}`
  }
  return config
})

// 统一处理业务错误码
function handleBizError(res) {
  if (res.code === 401) {
    const userStore = useUserStore()
    userStore.clearAuth()
    ElMessage.error(res.message || '登录已过期，请重新登录')
    router.push('/login')
  } else if (res.code === 403) {
    ElMessage.error(res.message || '无权限访问')
  } else {
    ElMessage.error(res.message || '请求失败')
  }
}

// 响应拦截：统一处理业务码（后端异常也返回 HTTP 200，靠 code 区分）
request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code !== 200) {
      handleBizError(res)
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  (error) => {
    const res = error.response?.data
    if (res && res.code) {
      handleBizError(res)
    } else {
      ElMessage.error('网络异常，请稍后重试')
    }
    return Promise.reject(error)
  }
)

export default request
