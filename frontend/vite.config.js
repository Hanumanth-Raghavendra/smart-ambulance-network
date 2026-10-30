import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies the API and the WebSocket to the Spring Boot backend, so there are no CORS problems.
const backend = process.env.BACKEND_URL || 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: backend, changeOrigin: true },
      '/ws': { target: backend, ws: true, changeOrigin: true },
    },
  },
});
