import request from '@/utils/request'

// 登录：成功返回 { token, user }
export function login(data) {
  return request.post('/user/login', data)
}

// 退出登录
export function logout() {
  return request.post('/user/logout')
}

// 获取当前用户信息
export function getInfo() {
  return request.get('/user/info')
}
