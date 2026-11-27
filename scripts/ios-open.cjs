const {openUrlAsync}=require('../node_modules/expo/node_modules/@expo/cli/build/src/start/platforms/ios/simctl');
const device=require('fs').readFileSync('artifacts/ios-device.txt','utf8').trim();
const page=process.argv[2]||'today';
const url=page==='__development'?'exp+whumpo-expo-mock://?__expo_url=http%3A%2F%2F127.0.0.1%3A8619&__expo_disable_fab=1&__expo_disable_auto_launch=1&__expo_disable_onboarding=1':'whumpoexpo://'+page;
openUrlAsync({udid:device},{appId:'com.whumpo.expomock',url}).catch(e=>{console.error(e);process.exit(1)});
