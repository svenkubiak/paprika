import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Paprika Docs',
  description: 'How to use the Paprika admin UI: tenants, roles, collections, rules, hooks, and more.',
  base: '/paprika/',
  cleanUrls: true,
  lastUpdated: true,

  themeConfig: {
    nav: [
      { text: 'Installation', link: '/installation/docker' },
      { text: 'Concepts', link: '/concepts/tenants' },
      { text: 'Admin UI Guide', link: '/admin-ui/dashboard' },
      { text: 'Operations', link: '/operations/going-to-production' },
      { text: 'GitHub', link: 'https://github.com/svenkubiak/paprika' }
    ],

    sidebar: [
      {
        text: 'Installation',
        items: [
          { text: 'Docker', link: '/installation/docker' },
          { text: 'Standalone (.deb)', link: '/installation/standalone' },
          { text: 'Initial Setup', link: '/installation/initial-setup' },
          { text: 'Configuration', link: '/installation/configuration' }
        ]
      },
      {
        text: 'Concepts',
        items: [
          { text: 'Tenants', link: '/concepts/tenants' },
          { text: 'Roles & Permissions', link: '/concepts/roles-and-permissions' },
          { text: 'Collections', link: '/concepts/collections' }
        ]
      },
      {
        text: 'Admin UI Guide',
        items: [
          { text: 'Dashboard', link: '/admin-ui/dashboard' },
          { text: 'Tenants', link: '/admin-ui/tenants' },
          { text: 'Tenant Users', link: '/admin-ui/tenant-users' },
          { text: 'Auth', link: '/admin-ui/auth-settings' },
          {
            text: 'Collections',
            collapsed: false,
            items: [
              { text: 'Data', link: '/admin-ui/collection-data' },
              { text: 'Schema', link: '/admin-ui/collection-schema' },
              { text: 'Rules', link: '/admin-ui/collection-rules' },
              { text: 'Hooks', link: '/admin-ui/collection-hooks' },
              { text: 'API Reference', link: '/admin-ui/collection-api' }
            ]
          },
          { text: 'Global Hooks', link: '/admin-ui/global-hooks' },
          { text: 'Superadmins', link: '/admin-ui/superadmins' },
          { text: 'Settings', link: '/admin-ui/settings' },
          { text: 'Request Logs', link: '/admin-ui/request-logs' },
          { text: 'Backup & Restore', link: '/admin-ui/backup-restore' }
        ]
      },
      {
        text: 'Operations',
        items: [
          { text: 'Going to Production', link: '/operations/going-to-production' }
        ]
      },
      {
        text: 'Contributing',
        items: [
          { text: 'Local Development', link: '/contributing/local-development' }
        ]
      }
    ],

    socialLinks: [{ icon: 'github', link: 'https://github.com/svenkubiak/paprika' }],

    search: {
      provider: 'local'
    },

    editLink: {
      pattern: 'https://github.com/svenkubiak/paprika/edit/main/docs/:path'
    }
  }
})
