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
      },
      {
        path: 'policy',
        name: 'PolicyList',
        component: () => import('@/views/policy/index.vue'),
        meta: { title: '制度知识库', icon: 'Notebook' }
      },
      {
        path: 'work-events',
        name: 'WorkEventList',
        component: () => import('@/views/workEvent/index.vue'),
        meta: { title: '工作事件复核', icon: 'Calendar' }
      },
      {
        // 分析详情：轨迹线 + 停留点 + 事件时序同屏（复核判断的上下文都在这）
        path: 'work-events/:trackId',
        name: 'WorkEventDetail',
        component: () => import('@/views/workEvent/detail.vue'),
        meta: { title: '分析详情', icon: 'MapLocation', activeMenu: '/work-events' }
      },
      {
        path: 'rules',
        name: 'RuleList',
        component: () => import('@/views/rule/index.vue'),
        meta: { title: '合规规则', icon: 'SetUp' }
      },
      {
        path: 'orders',
        name: 'OrderList',
        component: () => import('@/views/order/index.vue'),
        meta: { title: '整改工单', icon: 'Tickets' }
      },
      {
        path: 'model-providers',
        name: 'ModelProviderConfig',
        component: () => import('@/views/modelProvider/index.vue'),
        meta: { title: '模型接入', icon: 'Connection' }
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
  // 模型接入页仅超级管理员可进。后端 Service 层同样校验，这里只是提前拦住避免白跳一次
  if (to.path === '/model-providers' && !userStore.isSuperAdmin) {
    ElMessage.error('仅超级管理员可访问模型接入配置')
    return '/dashboard'
  }
  return true
})

export default router
