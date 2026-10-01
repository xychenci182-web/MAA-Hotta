# App 鍐呮洿鏂?
杩愯鎺у埗涓偣鍑烩€滄鏌ユ洿鏂扳€濓紝濉啓 HTTPS 鏇存柊璇存槑鍦板潃銆備繚瀛樺苟妫€鏌ュ悗锛屽彲鏌ョ湅鏂扮増鏈鏄庛€佷笅杞姐€佹牎楠屽苟鐐瑰嚮瀹夎銆傞娆″畨瑁呴渶瑕佸湪绯荤粺璁剧疆鍏佽姝ゅ簲鐢ㄥ畨瑁呮湭鐭ユ潵婧愬簲鐢紝鐒跺悗杩斿洖鍐嶆鐐瑰嚮瀹夎銆傛洿鏂拌鐩栧畨瑁咃紝璐﹀彿閰嶇疆淇濈暀锛涗笉瑕佸厛鍗歌浇鏃х増銆?
褰撳墠鐗堟湰涓?0.2.0锛寁ersionCode 涓?2銆傛棫鐗堥娆￠渶鎵嬪姩瀹夎鏈増锛屼箣鍚庢墠鑳戒娇鐢ㄥ唴缃洿鏂般€?
## GitHub 鎵樼鍦板潃锛堝綋鍓嶏級

- 妫€鏌ユ洿鏂板湴鍧€锛堝浐瀹氾級锛歚https://xychenci182-web.github.io/MAA-Hotta/update.json`
- APK 涓嬭浇锛堝甫鐗堟湰鍙凤級锛歚https://github.com/xychenci182-web/MAA-Hotta/releases/download/v0.2.1/mah.apk`
- 浠撳簱锛歚https://github.com/xychenci182-web/MAA-Hotta`

鍦?App 閲屾妸鏇存柊璇存槑濉垚涓婇潰鐨勫浐瀹?`update.json` 鍦板潃鍗冲彲銆?
## 鍙戝竷涓嬩竴鐗?
1. 淇敼 `app/build.gradle.kts`锛氶€掑 versionCode锛屾洿鏂?versionName銆?2. 浣跨敤鍚屼竴绛惧悕瀵嗛挜鏋勫缓 APK銆傚綋鍓嶅畨瑁呭寘鏄?Debug 绛惧悕锛屽悗缁』淇濈暀鍚屼竴鍙版瀯寤烘満鍣ㄧ殑 debug.keystore锛涙崲绛惧悕涓嶈兘鐩存帴瑕嗙洊銆?3. 鏋勫缓鎴愬姛鍚庣敓鎴愬彂甯冩枃浠讹紙鎶婄増鏈彿鏀规垚鏂扮殑锛夛細

```powershell
.\tools\prepare-update.ps1 -ApkUrl 'https://github.com/xychenci182-web/MAA-Hotta/releases/download/vX.Y.Z/mah.apk' -Notes '鏈鏇存柊璇存槑'
```

4. 涓婁紶瀹夎鍖呭埌 GitHub Release锛?
```powershell
gh release create vX.Y.Z dist/update/mah.apk --title "vX.Y.Z" --notes "鏈鏇存柊璇存槑"
```

5. 瑕嗙洊鍥哄畾鏇存柊璇存槑骞舵帹閫侊細

```powershell
Copy-Item dist/update/update.json docs/updates/update.json -Force
git add docs/updates/update.json
git commit --trailer "Co-authored-by: Cursor <cursoragent@cursor.com>" -m "Bump update manifest to vX.Y.Z"
git push
```

6. 鍦?App 涓厤缃浐瀹氭洿鏂拌鏄庡湴鍧€锛岀偣鍑绘鏌ユ洿鏂般€?
鏇存柊璇存槑鍖呭惈 versionCode銆乿ersionName銆乤pkUrl銆乻ha256銆乶otes銆傝剼鏈粠 APK 鏋勫缓鍏冩暟鎹彁鍙栫増鏈苟璁＄畻 SHA-256锛岄伩鍏嶆墜濉敊璇€侫pp 鎷掔粷闈?HTTPS銆侀檷绾с€佸寘鍚嶄笉绗︺€佸搱甯屼笉绗﹀拰绛惧悕涓嶅悓鐨勫畨瑁呭寘銆傚畨瑁呮搷浣滈€氳繃 Android 绯荤粺瀹夎鍣ㄧ敱鐢ㄦ埛纭銆?
涓嬭浇澶辫触鍙互閲嶆柊涓嬭浇锛涜繘绋嬭绯荤粺缁堟鍚庨渶閲嶆柊妫€鏌ュ拰涓嬭浇锛屼笉鎻愪緵鏂偣缁紶鎴栭潤榛樺畨瑁呫€