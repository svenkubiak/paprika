import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import ui from '@nuxt/ui/vite'
import { fileURLToPath, URL } from 'node:url'

const assetsOutDir = fileURLToPath(new URL('../src/main/resources/files/assets', import.meta.url))

/**
 * Nuxt UI writes colored text in the 500 shade; the text-readable utilities in main.css explain
 * the replacement. Spelled out, because Tailwind only generates classes its scanner finds.
 */
const readableText = {
  primary: 'text-readable-primary',
  secondary: 'text-readable-secondary',
  success: 'text-readable-success',
  info: 'text-readable-info',
  warning: 'text-readable-warning',
  error: 'text-readable-error'
}

/**
 * `slot` is for components whose compound variants style a slot rather than the root class;
 * without `variants` the rule applies to every variant of the color.
 */
function readableTextFor(variants: string[] | null, slot?: string) {
  return Object.entries(readableText).flatMap(([color, className]) => {
    const value = slot ? { [slot]: className } : className
    return variants ? variants.map((variant) => ({ color, variant, class: value })) : [{ color, class: value }]
  })
}

/**
 * Solid buttons put white text on the 500 shade, 3.8:1 for blue and red in light mode, and the
 * hover lightens it further. In light mode they use the first shade that reaches 4.5:1 against
 * white (blue and red 600, green and yellow 700) and darken one step on hover. Dark mode keeps
 * Nuxt UI's colors: dark text on the 400 shade already reaches 6:1.
 */
const readableSolid = {
  primary: 'bg-(color:--ui-color-primary-600) hover:bg-(color:--ui-color-primary-700) active:bg-(color:--ui-color-primary-700) disabled:bg-(color:--ui-color-primary-600) aria-disabled:bg-(color:--ui-color-primary-600) dark:bg-primary dark:hover:bg-primary/75 dark:active:bg-primary/75 dark:disabled:bg-primary dark:aria-disabled:bg-primary',
  secondary: 'bg-(color:--ui-color-secondary-600) hover:bg-(color:--ui-color-secondary-700) active:bg-(color:--ui-color-secondary-700) disabled:bg-(color:--ui-color-secondary-600) aria-disabled:bg-(color:--ui-color-secondary-600) dark:bg-secondary dark:hover:bg-secondary/75 dark:active:bg-secondary/75 dark:disabled:bg-secondary dark:aria-disabled:bg-secondary',
  success: 'bg-(color:--ui-color-success-700) hover:bg-(color:--ui-color-success-800) active:bg-(color:--ui-color-success-800) disabled:bg-(color:--ui-color-success-700) aria-disabled:bg-(color:--ui-color-success-700) dark:bg-success dark:hover:bg-success/75 dark:active:bg-success/75 dark:disabled:bg-success dark:aria-disabled:bg-success',
  info: 'bg-(color:--ui-color-info-600) hover:bg-(color:--ui-color-info-700) active:bg-(color:--ui-color-info-700) disabled:bg-(color:--ui-color-info-600) aria-disabled:bg-(color:--ui-color-info-600) dark:bg-info dark:hover:bg-info/75 dark:active:bg-info/75 dark:disabled:bg-info dark:aria-disabled:bg-info',
  warning: 'bg-(color:--ui-color-warning-700) hover:bg-(color:--ui-color-warning-800) active:bg-(color:--ui-color-warning-800) disabled:bg-(color:--ui-color-warning-700) aria-disabled:bg-(color:--ui-color-warning-700) dark:bg-warning dark:hover:bg-warning/75 dark:active:bg-warning/75 dark:disabled:bg-warning dark:aria-disabled:bg-warning',
  error: 'bg-(color:--ui-color-error-600) hover:bg-(color:--ui-color-error-700) active:bg-(color:--ui-color-error-700) disabled:bg-(color:--ui-color-error-600) aria-disabled:bg-(color:--ui-color-error-600) dark:bg-error dark:hover:bg-error/75 dark:active:bg-error/75 dark:disabled:bg-error dark:aria-disabled:bg-error'
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
            root: 'w-full max-w-full',
            error: 'text-readable-error'
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
        alert: {
          compoundVariants: readableTextFor(['soft', 'subtle', 'outline'], 'root')
        },
        // A disabled switch dims only the toggle: Nuxt UI fades the whole root, which takes the
        // description down to 2.9:1, and it often says why the switch is locked.
        switch: {
          variants: {
            disabled: {
              true: {
                root: 'opacity-100',
                base: 'opacity-75'
              }
            }
          }
        },
        // The status icon of a toast; green and yellow stay below 3:1 in the 500 shade.
        toast: {
          compoundVariants: readableTextFor(null, 'icon')
        },
        button: {
          compoundVariants: [
            ...readableTextFor(['soft', 'subtle', 'outline', 'ghost']),
            ...Object.entries(readableSolid).map(([color, className]) => ({
              color,
              variant: 'solid',
              class: className
            }))
          ]
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
