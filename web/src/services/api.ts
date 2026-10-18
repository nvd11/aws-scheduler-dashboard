import axios from 'axios';

export const API_BASE_URL = '/scheduler/api';

export const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  withCredentials: true,
});

export function handleUnauthorizedRedirect(pathname: string = window.location.pathname): string {
  const returnPath = pathname.startsWith('/scheduler') ? pathname : '/scheduler/';
  return `/oauth2/start?rd=${encodeURIComponent(returnPath)}`;
}

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error?.response?.status === 401) {
      const target = handleUnauthorizedRedirect();
      if (typeof window !== 'undefined' && window.location) {
        window.location.assign(target);
      }
    }
    return Promise.reject(error);
  }
);

export default api;
