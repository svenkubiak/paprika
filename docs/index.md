---
layout: home

hero:
  name: Paprika
  text: Admin UI & Concepts Guide
  tagline: How the multi-tenant BaaS admin UI works, once it's up and running.
  actions:
    - theme: brand
      text: Understand the concepts
      link: /concepts/tenants
    - theme: alt
      text: Browse the Admin UI Guide
      link: /admin-ui/dashboard
    - theme: alt
      text: Installation
      link: /installation/docker

features:
  - title: Tenants
    details: Every tenant gets its own MongoDB database, its own users, and its own registration settings.
    link: /concepts/tenants
  - title: Roles & Permissions
    details: Superadmin vs. tenant user, and the per-collection rule engine that actually governs API access.
    link: /concepts/roles-and-permissions
  - title: Collections
    details: Fields, validation, indexes, relations, and files — the schema model behind every API.
    link: /concepts/collections
---
