#!/system/bin/sh
ui_print "整屏惯性漂移：Beta 测试版"
[ "$API" = "36" ] || abort "仅支持已验证的 Android 16 / API 36"
[ "$(getprop ro.soc.model)" = "SM8750" ] || abort "此实验包限定 SM8750，其他硬件未验证"
[ "$(wm size | head -n 1)" = "Physical size: 1200x2670" ] || abort "屏幕尺寸不匹配"
ui_print "默认：紧致手感、120Hz目标、110px限幅"
ui_print "仅亮屏竖屏工作，停用/卸载后必须重启"
ui_print "触摸对齐、功耗和异常场景尚未完整验证"
set_perm_recursive "$MODPATH" 0 0 0700 0600
set_perm "$MODPATH/service.sh" 0 0 0700
