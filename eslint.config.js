const { defineConfig, globalIgnores } = require('eslint/config');
const expoConfig = require('eslint-config-expo/flat');
module.exports = defineConfig([
  globalIgnores(['v9-snapshot/**','v10-snapshot/**','v11-snapshot/**','v12-snapshot/**','v13-snapshot/**', 'v8-snapshot/**', 'artifacts/**', 'compare/**', 'screenshots/**', '.venv/**', 'v7-snapshot/**', 'v6-snapshot/**', '.native-cache/**']),
  expoConfig,
]);
