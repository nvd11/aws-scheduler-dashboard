import { ApiResponse, UserProfile } from '@/types/scheduler';
import { api, handleUnauthorizedRedirect } from './api';

export const authService = {
  async getCurrentUser(): Promise<UserProfile | null> {
    try {
      const res = await api.get<ApiResponse<UserProfile>>('/auth/me');
      if (res.data && res.data.code === 0) {
        return res.data.data;
      }
      return null;
    } catch {
      return null;
    }
  },

  gatewayLogout(): void {
    window.location.assign('/oauth2/sign_out?rd=/scheduler/');
  },

  gatewayLogin(): void {
    window.location.assign(handleUnauthorizedRedirect(window.location.pathname));
  },
};
