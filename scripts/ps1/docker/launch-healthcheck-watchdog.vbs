' VBS wrapper:隐藏窗口启动 PowerShell 脚本(避免 schtasks 每分钟 pwsh 闪窗)
' 用法(schtasks):wscript "C:\Users\aa\scripts\launch-healthcheck-watchdog.vbs"
CreateObject("Wscript.Shell").Run "pwsh -NoProfile -ExecutionPolicy Bypass -File ""C:\Users\aa\scripts${n}.ps1""", 0, False
