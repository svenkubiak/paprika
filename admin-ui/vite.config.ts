import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import ui from '@nuxt/ui/vite'
import { fileURLToPath, URL } from 'node:url'

const assetsOutDir = fileURLToPath(new URL('../src/main/resources/files/assets', import.meta.url))

export default defineConfig({
  plugins: [
    vue(),
    ui({
      icon: {
        clientBundle: {
          scan: true,
          icons: [
            // Field-type icons (resolved dynamically via fieldTypeIcon(); scan cannot detect them)
            'lucide:type', 'lucide:hash', 'lucide:toggle-left', 'lucide:calendar', 'lucide:clock',
            'lucide:git-branch', 'lucide:paperclip', 'lucide:braces',
            'lucide:activity', 'lucide:archive', 'lucide:arrow-down-left', 'lucide:arrow-left',
            'lucide:arrow-right-left', 'lucide:arrow-up-down', 'lucide:arrow-up-right',
            'lucide:building-2', 'lucide:calendar-clock', 'lucide:check', 'lucide:chevron-down',
            'lucide:chevron-left', 'lucide:chevron-right', 'lucide:chevron-up', 'lucide:circle-check',
            'lucide:circle-question-mark', 'lucide:circle-x', 'lucide:code', 'lucide:columns-3',
            'lucide:copy', 'lucide:database', 'lucide:download', 'lucide:eye', 'lucide:eye-off',
            'lucide:file-text', 'lucide:flask-conical', 'lucide:globe', 'lucide:info',
            'lucide:key-round', 'lucide:layers', 'lucide:layout-dashboard', 'lucide:link',
            'lucide:list-checks', 'lucide:list-ordered', 'lucide:loader-circle', 'lucide:lock',
            'lucide:lock-keyhole', 'lucide:log-in', 'lucide:log-out', 'lucide:mail', 'lucide:menu',
            'lucide:moon', 'lucide:pencil', 'lucide:plug', 'lucide:plus', 'lucide:radio',
            'lucide:reply', 'lucide:save', 'lucide:scroll-text', 'lucide:search', 'lucide:settings',
            'lucide:shield', 'lucide:shield-check', 'lucide:shield-off', 'lucide:shield-plus',
            'lucide:sliders-horizontal', 'lucide:sun', 'lucide:table-2', 'lucide:tag',
            'lucide:trash-2', 'lucide:triangle-alert', 'lucide:upload', 'lucide:user',
            'lucide:user-check', 'lucide:user-cog', 'lucide:user-plus', 'lucide:users',
            'lucide:webhook', 'lucide:x', 'lucide:zap'
          ]
        }
      },
      ui: {
        colors: {
          primary: 'blue',
          neutral: 'zinc'
        },
        formField: {
          slots: {
            root: 'w-full max-w-full'
          }
        },
        input: {
          slots: {
            root: 'w-full max-w-full'
          }
        },
        select: {
          slots: {
            base: 'w-full max-w-full'
          }
        },
        textarea: {
          slots: {
            root: 'w-full max-w-full'
          }
        }
      },
      components: {
        dirs: ['src/components', 'src/layouts']
      }
    })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  build: {
    manifest: 'manifest.json',
    outDir: assetsOutDir,
    chunkSizeWarningLimit: 600,
    emptyOutDir: false,
    rollupOptions: {
      output: {
        entryFileNames: 'js/[name]-[hash].js',
        chunkFileNames: 'js/[name]-[hash].js',
        assetFileNames: (assetInfo) => {
          const name = assetInfo.names?.[0] ?? assetInfo.name ?? 'asset'
          if (name.endsWith('.css')) {
            return 'css/[name]-[hash][extname]'
          }
          if (/\.(png|jpe?g|svg|gif|webp|ico)$/i.test(name)) {
            return 'img/[name]-[hash][extname]'
          }
          return 'img/[name]-[hash][extname]'
        }
      }
    }
  },
  base: '/assets/',
  server: {
    proxy: {
      '/admin': 'http://localhost:9090',
      '/api': 'http://localhost:9090',
      '/authenticate': 'http://localhost:9090',
      '/logout': 'http://localhost:9090',
      '/login': 'http://localhost:9090'
    }
  }
})
