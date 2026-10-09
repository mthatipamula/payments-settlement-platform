import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const target = process.env.VITE_API_TARGET ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: { port: 5174, proxy: { '/api': target, '/actuator': target } },
})
