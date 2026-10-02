# 识别模板（打进 APK）

截好的 PNG **放到工程里**，重新编译后会打进安装包：

`C:\Users\13367\MAA-Hotta\app\src\main\assets\templates\`

Android Studio 左侧：`app` → `src` → `main` → `assets` → `templates`

只截按钮/输入框本身，不要整屏。文件名必须和表里完全一致。

放好后重新 **构建 → Generate APKs**，再把新的 `app-debug.apk` 装进 MuMu。

（可选）装好之后若要改图、不想重编，可覆盖到  
`Android/data/com.aliothmoon.maahotta/files/templates/`  
平时不用管这个目录。

| 文件名 | 用途 |
| --- | --- |
| pwd_agree.png | 完美世界「已阅读并同意」圆点 |
| pwd_phone.png | 「请输入手机号」输入框 |
| pwd_next.png | 「下一步」 |
| pwd_logo.png | 「完美世界游戏」登录框标题 |
| pwd_password.png | 密码输入框 |
| pwd_login.png | 密码页「登录」 |
| announcement_close.png | 游戏公告右上角红色 X |
| announcement_title.png | 「游戏公告」标题（用来认出这页） |
| hud_gift_icon.png | 游戏内右上角礼盒图标（登录成功判断之一） |
| hud_crossed_icon.png | 游戏内右上角交叉图标（登录成功判断之一） |
| hud_gift_icon_new.png | 当前样式的游戏内礼盒图标，旧模板未命中时使用 |
| hud_crossed_icon_new.png | 当前样式的游戏内交叉图标，旧模板未命中时使用 |
| btn_enter_game.png | 进入游戏 / 点击进入 |
| hud_menu.png | 主界面菜单 / 已进世界 |
| btn_close.png | 关闭 |
| btn_confirm.png | 确认 |
| btn_skip.png | 跳过 |
| btn_agree.png | 游戏内弹窗同意 |
| btn_account_login.png | 账号登录 |
| menu_settings.png | 设置 |
| btn_logout.png | 退出登录 |
| hud_welfare.png | 福利宝箱 |
| tab_checkin.png | 签到页签 |
| btn_claim.png | 领取 |
| menu_adventure.png | 冒险 |
| entry_mia_kitchen.png | MIA 私厨入口 |
| btn_eat.png | 品尝 |
| mia_count_zero.png | MIA 私厨“品尝次数：0”中的数字0 |
| mia_completed.png | 必做页MIA私厨卡片的完成勾选 |
| hub_weekly_tab.png | 必做页左侧“每周”页签（确认仍在必做页） |
| hub_recommend_tab.png | 必做页左侧“推荐”页签（MIA私厨和次元历练共用） |
| hub_leisure_tab.png | 必做页左侧“休闲”页签（人工岛任务） |
| hub_challenge_tab.png | 必做页左侧“挑战”页签（旧日幻想任务） |
| entry_dimension_trial.png | 次元历练入口 |
| trials_dialog_logo.png | 次元历练窗口左上角标题 |
| trials_participate.png | 次元历练“参与”按钮 |
| trials_proxy_battle.png | 快捷战斗弹窗“代理战斗”按钮 |
| trials_result_success.png | 次元历练“作战成功”文字 |
| trials_close.png | 次元历练窗口右上角X |
| trials_vitality_insufficient.png | 次元历练“当前活力不足，将无法获得结算奖励”红字 |
| btn_sweep.png | 扫荡 |
| btn_plus.png | 增加次数 |
| text_no_stamina.png | 体力不足 |
| text_no_times.png | 次数不足 |
| leisure_page_anchor.png | 休闲页中央“世界BOSS”卡片（进入成功验证） |
| entry_island_build.png | 休闲页中央“人工岛建筑”完整卡片 |
| island_build_red_dot.png | 人工岛建筑卡片右上角红点（先检查红点，有红点再进入页面） |
| island_page_title.png | 人工岛页面左上角“我的人工岛”标题 |
| island_merchant_portrait.png | 人工岛页面右侧老头头像 |
| entry_bygone_phantasm.png | 挑战页中央“旧日幻想”完整卡片 |
| bygone_dive_next.png | 旧日幻想详情页“潜入下一层”按钮 |
| bygone_skip.png | 旧日幻想入场动画右上角“跳过”按钮 |
| bygone_exit_icon.png | 旧日幻想副本左上角退出图标 |
| bygone_exit_dialog.png | “是否离开旧日幻想副本”确认文字 |
| bygone_exit_confirm.png | 旧日幻想退出弹窗“确定”按钮 |
| hud_hex_menu_icon.png | 游戏主界面右上角六边形菜单图标 |
| guild_menu_entry.png | 展开菜单中的“公会”按键 |
| guild_daily_tab.png | 公会页面底部“日常”页签 |
| guild_donate_now.png | 公会日常“立即捐献”按钮 |
| guild_donate_confirm_text.png | “是否确认捐献1个”确认文字 |
| guild_donate_confirm.png | 捐献确认弹窗“确定”按钮 |
| guild_donate_zero.png | 公会可捐献次数“0/1”状态 |
| guild_donate_one.png | 公会可捐献次数“1/1”状态，用于排除0/1误判 |
| guild_info_tab.png | 公会页面底部“信息”页签 |
| guild_rewards_row.png | 公会信息页顶部六个奖励栏 |
| guild_weekly_open.png | 公会福利页右下角 OPEN 周奖励按钮 |
| menu_social_entry.png | 展开菜单中的“社交”按键 |
| social_mail_tab.png | 社交页左侧未选中的“邮件”入口 |
| social_mail_selected.png | 邮件页左侧选中的“邮件”页签 |
| mail_claim_all.png | 邮件页右下角“一键领取”按钮 |
| mail_reward_popup.png | 邮件奖励弹层固定的“恭喜获得”标题 |
| menu_settings_entry.png | 展开菜单中的“设置”按键 |
| settings_user_center.png | 设置页顶部“用户中心”按键，同时验证已进入设置页 |

`welfare_page_title.png`：福利页左上角“福利”标题，528 像素基准高度；保留白色字形和一像素轮廓。导航判定需同时确认标题与底栏/内容，浅色底栏不能单独判定为福利页。
