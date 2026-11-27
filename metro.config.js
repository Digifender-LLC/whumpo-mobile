const {getDefaultConfig} = require('expo/metro-config');
const path=require('path');
const config = getDefaultConfig(__dirname);
// Generated evidence must not trigger Fast Refresh while native UI is recorded.
const outputs=['v6-snapshot','v7-snapshot','v8-snapshot','v9-snapshot','v10-snapshot','v11-snapshot','v12-snapshot','v13-snapshot','.native-cache','.native-build','.venv','artifacts','screenshots','compare','review-v9-sonnet','scripts','android','ios','__tests__'];
config.resolver.blockList=outputs.map(name=>new RegExp('^'+path.join(__dirname,name).replace(/[.*+?^${}()|[\]\\]/g,'\\$&')+'(?:/|$)'));
config.resolver.useWatchman = false;
module.exports = config;
