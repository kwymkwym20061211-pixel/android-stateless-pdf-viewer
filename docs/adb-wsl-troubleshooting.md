# WSL経由ADB接続トラブルシューティング記録

## 環境

- ホストOS: Windows 11
- WSL2ディストリビューション: Ubuntu
- 接続端末: FCNT製スマートフォン（FCG02、VID: `30ee`）
- ネットワーク: KUINS-Air 2（大学WiFi）
- usbipd-win: 5.3.0

---

## 症状

`adb devices` をWSL側で実行しても何も表示されない。  
Windows側でも `adb devices` が空だった。

---

## 試行錯誤の履歴

### ① Windowsのadb devicesが空

```
C:\Users\admin> adb devices
List of devices attached
```

**原因候補**: ケーブル不良、ドライバ未インストール、スマホ側の許可未設定。

---

### ② Windowsのデバイスマネージャーで確認

スマホは「ポータブルデバイス」として認識されていた。
製造元: FCNT。MTPドライバは当たっていたがADBドライバが当たっていなかった。

**対処**: デバイスマネージャーで自動検索によりドライバを更新 → Windows側でADB認識に成功。

```
C:\Users\admin> adb devices
List of devices attached
7LVKKZAMU8EYXKWG        device
```

---

### ③ WSL側では依然として空

`.bashrc` に以下の設定があり、ワイヤレスADB接続を試みていた。

```bash
if ! timeout 2s adb connect 192.168.11.10:5555 > /dev/null 2>&1; then
    echo -e "\e[31m[!] Warning: ADB connection failed (192.168.11.10:5555). Check your device.\e[0m"
```

**問題**: スマホのIPが以前の `192.168.11.10`（自宅LAN）から `10.237.238.98`（KUINS）に変わっていた。

---

### ④ ワイヤレスADBを試みる

```
C:\Users\admin> adb tcpip 5555
restarting in TCP mode port: 5555
```

```bash
# WSL側
adb connect 192.168.11.10:5555
# → 固まる（タイムアウト）
```

**原因**: WSL2のネットワークは `172.18.96.0/20` のNATセグメントにあり、`192.168.11.0/24` へのルートがなかった。

---

### ⑤ WSLのルーティングを追加

```bash
sudo ip route add 192.168.11.0/24 via 172.18.96.1
ping -c 2 192.168.11.10
# → 100% packet loss（到達不能）
```

**原因**: WSL2のデフォルトゲートウェイ（`172.18.96.1`）はWindowsの仮想アダプターだが、WindowsがWSLネットワークと物理LAN間のルーティングを行っていなかった。

---

### ⑥ スマホのIPが変わっていたことを確認

スマホのWiFi設定で現在のIPを確認:

```
10.237.238.98
```

`192.168.11.10` は以前の自宅ネットワークのIPだった。

---

### ⑦ 正しいIPでワイヤレスADBを試みる

```
C:\Users\admin> adb connect 10.237.238.98:5555
cannot connect to 10.237.238.98:5555: 接続済みの呼び出し先が一定の時間を過ぎても正しく応答しなかったため、接続できませんでした。
```

**原因**: KUINS-Air 2（大学WiFi）はクライアント間通信を遮断している（APアイソレーション）。同じWiFiに接続したWindows PCとスマホが直接通信できない。

→ **ワイヤレスADBはこのネットワークでは根本的に使用不可。**

---

### ⑧ WindowsのADBサーバーをWSLから使う試み

WindowsのADBサーバー（USB接続済み）にWSLから接続することを試みた。

```bash
# Windowsで
set ADB_SERVER_SOCKET=tcp:0.0.0.0:5037
adb start-server
# → エラー: cannot connect to 0.0.0.0:5037 (10049)
```

`tcp:0.0.0.0:5037` はWindowsでは無効な構文だった。

---

### ⑨ adb -a nodaemon server で全インターフェースリッスン

別ターミナルで実行:

```
adb -a nodaemon server
```

```bash
# WSLから
adb -H 172.18.96.1 -P 5037 devices
# → 固まる
```

**原因**: Windowsファイアウォールがポート5037をブロックしていた。

---

### ⑩ ファイアウォール規則の追加

```powershell
New-NetFirewallRule -DisplayName "ADB for WSL" -Direction Inbound -Protocol TCP -LocalPort 5037 -Action Allow
```

```bash
# WSLから再テスト
timeout 3 bash -c 'echo "" > /dev/tcp/172.18.96.1/5037' && echo "open" || echo "blocked"
# → blocked
```

規則を追加してもブロックされ続けた。

**推定原因**: Windowsが `adb.exe` 用のアプリケーションレベルのファイアウォールブロックルールを持っており、ポートベースの許可ルールより優先された可能性。

---

### ⑪ netsh portproxyで迂回を試みる

ポート5037ではなく別ポート経由で転送:

```powershell
netsh interface portproxy add v4tov4 listenport=5556 listenaddress=172.18.96.1 connectport=5555 connectaddress=10.237.238.98
New-NetFirewallRule -DisplayName "ADB Proxy WSL" -Direction Inbound -Protocol TCP -LocalPort 5556 -Action Allow
```

```bash
# WSLから
timeout 3 bash -c 'echo "" > /dev/tcp/172.18.96.1/5556' && echo "port open" || echo "blocked"
# → port open

adb connect 172.18.96.1:5556
# → already connected to 172.18.96.1:5556（ただしoffline）
```

**問題**: スマホの `10.237.238.98:5555` 自体に接続できなかった（前述のAPアイソレーション問題）。

---

### ⑫ usbipd-winを使用（最終的な解決策）

usbipd-winはMicrosoftが推奨するWSL2向けUSBフォワーディングツール。すでにバージョン5.3.0がインストール済みだった。

```
C:\Users\admin> usbipd list
BUSID  VID:PID    DEVICE                         STATE
1-3    30ee:1167  FCG02, ADB Interface            Not shared
```

```powershell
usbipd bind --busid 1-3
adb kill-server  # WindowsのADBサーバーを停止しないとデバイスが占有されている
usbipd attach --wsl --busid 1-3
```

```bash
# WSL側（udev規則が未設定のためno permissionsエラー）
adb devices
# → 7LVKKZAMU8EYXKWG  no permissions
```

---

### ⑬ udevルールを追加

```bash
echo 'SUBSYSTEM=="usb", ATTR{idVendor}=="30ee", MODE="0666", GROUP="plugdev"' | sudo tee /etc/udev/rules.d/51-android.rules
sudo chmod a+r /etc/udev/rules.d/51-android.rules
sudo udevadm control --reload-rules
sudo udevadm trigger
adb kill-server
adb devices
# → 7LVKKZAMU8EYXKWG  device  ✓
```

**接続成功。**

---

## 最終的な原因分析

問題は一つではなく、複数の要因が重なっていた。

| # | 問題 | 原因 |
|---|------|------|
| 1 | WindowsのADB認識失敗 | ADBドライバ未インストール（MTPドライバのみ） |
| 2 | ワイヤレスADB失敗 | スマホIPの変更（192.168.11.10 → 10.237.238.98） |
| 3 | ワイヤレスADB根本不可 | KUINS-Air 2はAPアイソレーションでクライアント間通信を遮断 |
| 4 | WSL→Windows ADB（5037）失敗 | Windowsファイアウォールがadb.exeへの外部接続をブロック |
| 5 | WSLのudev未設定 | FCNT（VID: 30ee）のudev規則がなくno permissionsエラー |

---

## 次回接続時の手順

```powershell
# Windows（管理者不要）
adb kill-server
usbipd attach --wsl --busid 1-3
```

```bash
# WSL
adb devices
# → デバイスが表示されれば完了
```

> **注意**: usbipd attachはOS再起動やUSB抜き差しのたびに再実行が必要。  
> `-a` オプションで自動再接続が可能: `usbipd attach --wsl --busid 1-3 -a`

---

## インストール時の注意

`./build_android.sh` でインストールする際、スマホの画面がオフだとインストールが97%で止まる。  
**スマホの画面をオンにした状態でインストールすること。**
