import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(null)
  const isAuthenticated = computed(() => token.value !== null)

  // TODO(P2.B1): wire the admin auth flow — login, refresh rotation, and
  // role checks against the admin-scoped API (plan §14).
  return { token, isAuthenticated }
})