# App 閸愬懏娲块弬?
鏉╂劘顢戦幒褍鍩楁稉顓犲仯閸戠儵鈧粍顥呴弻銉︽纯閺傛壋鈧繐绱濇繅顐㈠晸 HTTPS 閺囧瓨鏌婄拠瀛樻閸︽澘娼冮妴鍌欑箽鐎涙ê鑻熷Λ鈧弻銉ユ倵閿涘苯褰查弻銉ф箙閺傛壆澧楅張顒冾嚛閺勫簺鈧椒绗呮潪濮愨偓浣圭墡妤犲苯鑻熼悙鐟板毊鐎瑰顥婇妴鍌烆浕濞嗏€崇暔鐟佸懘娓剁憰浣告躬缁崵绮虹拋鍓х枂閸忎浇顔忓銈呯安閻劌鐣ㄧ憗鍛弓閻儲娼靛┃鎰安閻㈩煉绱濋悞璺烘倵鏉╂柨娲栭崘宥嗩偧閻愮懓鍤€瑰顥婇妴鍌涙纯閺傛媽顩惄鏍х暔鐟佸拑绱濈拹锕€褰块柊宥囩枂娣囨繄鏆€閿涙稐绗夌憰浣稿帥閸楁瓕娴囬弮褏澧楅妴?
瑜版挸澧犻悧鍫熸拱娑?0.2.0閿涘瘉ersionCode 娑?2閵嗗倹妫悧鍫ヮ浕濞嗭繝娓堕幍瀣З鐎瑰顥婇張顒傚閿涘奔绠ｉ崥搴㈠閼虫垝濞囬悽銊ュ敶缂冾喗娲块弬鑸偓?
## GitHub 閹垫顓搁崷鏉挎絻閿涘牆缍嬮崜宥忕礆

- 濡偓閺屻儲娲块弬鏉挎勾閸р偓閿涘牆娴愮€规熬绱氶敍姝歨ttps://xychenci182-web.github.io/MAA-Hotta/update.json`
- APK 娑撳娴囬敍鍫濈敨閻楀牊婀伴崣鍑ょ礆閿涙瓪https://github.com/xychenci182-web/MAA-Hotta/releases/download/v0.2.2/mah.apk`
- 娴犳挸绨遍敍姝歨ttps://github.com/xychenci182-web/MAA-Hotta`

閸?App 闁插本濡搁弴瀛樻煀鐠囧瓨妲戞繅顐ｅ灇娑撳﹪娼伴惃鍕祼鐎?`update.json` 閸︽澘娼冮崡鍐插讲閵?
## 閸欐垵绔锋稉瀣╃閻?
1. 娣囶喗鏁?`app/build.gradle.kts`閿涙岸鈧帒顤?versionCode閿涘本娲块弬?versionName閵?2. 娴ｈ法鏁ら崥灞肩缁涙儳鎮曠€靛棝鎸滈弸鍕紦 APK閵嗗倸缍嬮崜宥呯暔鐟佸懎瀵橀弰?Debug 缁涙儳鎮曢敍灞芥倵缂侇參銆忔穱婵堟殌閸氬奔绔撮崣鐗堢€鐑樻簚閸ｃ劎娈?debug.keystore閿涙稒宕茬粵鎯ф倳娑撳秷鍏橀惄瀛樺复鐟曞棛娲婇妴?3. 閺嬪嫬缂撻幋鎰閸氬海鏁撻幋鎰絺鐢啯鏋冩禒璁圭礄閹跺﹦澧楅張顒€褰块弨瑙勫灇閺傛壆娈戦敍澶涚窗

```powershell
.\tools\prepare-update.ps1 -ApkUrl 'https://github.com/xychenci182-web/MAA-Hotta/releases/download/vX.Y.Z/mah.apk' -Notes '閺堫剚顐奸弴瀛樻煀鐠囧瓨妲?
```

4. 娑撳﹣绱剁€瑰顥婇崠鍛煂 GitHub Release閿?
```powershell
gh release create vX.Y.Z dist/update/mah.apk --title "vX.Y.Z" --notes "閺堫剚顐奸弴瀛樻煀鐠囧瓨妲?
```

5. 鐟曞棛娲婇崶鍝勭暰閺囧瓨鏌婄拠瀛樻楠炶埖甯归柅渚婄窗

```powershell
Copy-Item dist/update/update.json docs/updates/update.json -Force
git add docs/updates/update.json
git commit --trailer "Co-authored-by: Cursor <cursoragent@cursor.com>" -m "Bump update manifest to vX.Y.Z"
git push
```

6. 閸?App 娑擃參鍘ょ純顔兼祼鐎规碍娲块弬鎷岊嚛閺勫骸婀撮崸鈧敍宀€鍋ｉ崙缁橆梾閺屻儲娲块弬鑸偓?
閺囧瓨鏌婄拠瀛樻閸栧懎鎯?versionCode閵嗕箍ersionName閵嗕工pkUrl閵嗕够ha256閵嗕苟otes閵嗗倽鍓奸張顑跨矤 APK 閺嬪嫬缂撻崗鍐╂殶閹诡喗褰侀崣鏍閺堫剙鑻熺拋锛勭暬 SHA-256閿涘矂浼╅崗宥嗗婵夘偊鏁婄拠顖樷偓渚玴p 閹锋帞绮烽棃?HTTPS閵嗕線妾风痪褋鈧礁瀵橀崥宥勭瑝缁楋负鈧礁鎼辩敮灞肩瑝缁楋箑鎷扮粵鎯ф倳娑撳秴鎮撻惃鍕暔鐟佸懎瀵橀妴鍌氱暔鐟佸懏鎼锋担婊堚偓姘崇箖 Android 缁崵绮虹€瑰顥婇崳銊ф暠閻劍鍩涚涵顔款吇閵?
娑撳娴囨径杈Е閸欘垯浜掗柌宥嗘煀娑撳娴囬敍娑滅箻缁嬪顫︾化鑽ょ埠缂佸牊顒涢崥搴ㄦ付闁插秵鏌婂Λ鈧弻銉ユ嫲娑撳娴囬敍灞肩瑝閹绘劒绶甸弬顓犲仯缂侇厺绱堕幋鏍饯姒涙ê鐣ㄧ憗鍛偓