import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/',
    component: () => import('@/layout/AdminLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '仪表盘', icon: 'Odometer' }
      },
      {
        path: 'users',
        name: 'UserList',
        component: () => import('@/views/user/index.vue'),
        meta: { title: '用户管理', icon: 'User' }
      },
      {
        path: 'tracks',
        name: 'TrackList',
        component: () => import('@/views/track/index.vue'),
        meta: { title: '轨迹管理', icon: 'MapLocation' }
      },
      {
        path: 'tracks/:id',
        name: 'TrackDetail',
        component: () => import('@/views/track/detail.vue'),
        meta: { title: '轨迹详情', icon: 'MapLocation', activeMenu: '/tracks' }
      },
      {
        path: 'logs',
        name: 'LogList',
        component: () => import('@/views/log/index.vue'),
        meta: { title: '操作日志', icon: 'Document' }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫：无 token 跳登录；仅 ADMIN 可进入管理页
router.beforeEach((to) => {
  document.title = to.meta.title
    ? `${to.meta.title} - 工作轨迹管理端`
    : '工作轨迹管理端'

  const userStore = useUserStore()

  if (to.path === '/login') {
    // 已登录则不再停留在登录页
    return userStore.token ? '/dashboard' : true
  }
  if (!userStore.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (!userStore.isAdmin) {
    ElMessage.error('无权限访问')
    return '/login'
  }
  return true
})

export default router
