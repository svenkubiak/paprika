import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import ui from '@nuxt/ui/vite'
import { fileURLToPath, URL } from 'node:url'

const assetsOutDir = fileURLToPath(new URL('../src/main/resources/files/assets', import.meta.url))

/**
 * Nuxt UI writes colored text in the 500 shade, which stays below 4.5:1 on white and on the
 * tinted soft background (yellow 1.8:1, green 2.1:1, blue and red 3.3:1). The 800 shade in light
 * and the 300 shade in dark mode reach at least 6:1. Spelled out per color, so Tailwind's scanner
 * finds the classes in this file.
 */
const readableText = {
  primary: 'text-(color:--ui-color-primary-800) dark:text-(color:--ui-color-primary-300)',
  secondary: 'text-(color:--ui-color-secondary-800) dark:text-(color:--ui-color-secondary-300)',
  success: 'text-(color:--ui-color-success-800) dark:text-(color:--ui-color-success-300)',
  info: 'text-(color:--ui-color-info-800) dark:text-(color:--ui-color-info-300)',
  warning: 'text-(color:--ui-color-warning-800) dark:text-(color:--ui-color-warning-300)',
  error: 'text-(color:--ui-color-error-800) dark:text-(color:--ui-color-error-300)'
}

function readableTextFor(variants: string[]) {
  return Object.entries(readableText).flatMap(([color, className]) =>
    variants.map((variant) => ({ color, variant, class: className }))
  )
}

export default defineConfig({
  plugins: [
    vue(),
    ui({
      icon: {
        clientBundle: {
          scan: true,
          icons: [
            // Field-type icons (resolved dynamically via fieldTypeIcon(); scan cannot detect them)
            'lucide:type', 'lucide:align-left', 'lucide:hash', 'lucide:toggle-left',
            'lucide:calendar', 'lucide:clock', 'lucide:git-branch', 'lucide:paperclip',
            'lucide:braces',
            'lucide:activity', 'lucide:archive', 'lucide:bell', 'lucide:bell-off', 'lucide:send', 'lucide:arrow-down-left', 'lucide:arrow-left',
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
        },
        // Every tag is a soft badge in the default size.
        badge: {
          compoundVariants: readableTextFor(['soft'])
        },
        // Solid buttons keep white text on the 500 shade; only the colored-text variants change.
        button: {
          compoundVariants: readableTextFor(['soft', 'subtle', 'outline', 'ghost'])
        },
        // Nuxt UI only highlights rows that have a select handler; rows open via their Edit
        // button now, so the hover is set for every data row (the empty-state row has no
        // data-selectable attribute).
        // Pinned columns are see-through by default, so scrolled cells would shine through them.
        // Being opaque, they repeat the row hover as a solid mix of the same colors.
        // The inset shadow is the divider: a border would stay behind with the collapsed table.
        // A pinned checkbox cell keeps some end padding, which Nuxt UI drops for checkbox cells;
        // otherwise the divider sits right on the checkbox.
        table: {
          slots: {
            tbody: '[&>tr[data-selectable]]:hover:bg-elevated/50'
          },
          variants: {
            pinned: {
              true: {
                th: 'sticky z-1 bg-default data-[pinned=left]:shadow-[inset_-1px_0_0_var(--ui-border)] data-[pinned=right]:shadow-[inset_1px_0_0_var(--ui-border)] data-[pinned=left]:[&:has([role=checkbox])]:pe-3',
                td: 'sticky z-1 bg-default data-[pinned=left]:shadow-[inset_-1px_0_0_var(--ui-border)] data-[pinned=right]:shadow-[inset_1px_0_0_var(--ui-border)] data-[pinned=left]:[&:has([role=checkbox])]:pe-3 [tr:hover>&]:bg-[color-mix(in_oklab,var(--ui-bg-elevated)_50%,var(--ui-bg))]'
              }
            }
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
      '/logout': 'http://localhost:9090'
      // `/login` is deliberately not proxied: the backend would answer it with the *built*
      // index.html, whose hashed /assets/js/* files the dev server does not serve. Every full
      // page load onto the login page was a white page because of it. Vite's history fallback
      // serves the dev shell instead, and the login form posts to /api/admin/login anyway.
    }
  }
})
