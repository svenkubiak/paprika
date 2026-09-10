// Runs once, on first container start (MongoDB only executes scripts under
// /docker-entrypoint-initdb.d when /data/db is empty). Creates the scoped
// application user Paprika actually authenticates as; MONGO_INITDB_ROOT_USERNAME/
// PASSWORD stay bootstrap-only credentials, never handed to the app container.
db = db.getSiblingDB('admin');
db.createUser({
  user: process.env.MONGO_APP_USERNAME,
  pwd: process.env.MONGO_APP_PASSWORD,
  roles: [
    { role: 'readWriteAnyDatabase', db: 'admin' },
    { role: 'dbAdminAnyDatabase', db: 'admin' }
  ]
});
