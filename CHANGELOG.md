# Changelog

このプロジェクトの変更履歴です。形式は [Keep a Changelog](https://keepachangelog.com/ja/1.1.0/) に従います。

## [Unreleased]

## [0.1.0] - 2026-10-24

### Added

- 三井住友カード Vpass アプリ（`com.smbc_card.vpass`）向けの ReVanced パッチを追加しました。
- DexGuard の改ざん検知コールバック（Root、署名、チェックサム、デバッガ、エミュレータ、フック、動的計測、リソース改変）を無効化する `改ざん・Root検知の無効化` を追加しました。
- 起動時の `adb_enabled` 参照を 0 に固定し、USB デバッグ／ADB 有効時のブロック画面を表示させない `USBデバッグ検知の無効化` を追加しました。
- 上記 2 つをまとめて適用する `Vpass ReVanced` を追加しました。
- 再署名後の DexGuard 署名検証初期化を無効化し、パッチ版が起動できるようにしました。
- `patches.json` と GitHub Release による RVP 配布、DEX 検証スクリプト、CI を追加しました。

### Verified

- 5.3.0 / 5.7.0 / 5.8.3 / 5.9.0 / 5.11.0 / 5.14.1 でパッチ適用と DEX 検証に成功しました。
- AQUOS（SH-R80P / Android 16 / ADB 有効）で全バージョンが起動し、USB デバッグのブロック画面が表示されないことを確認しました。

### Known limitations

- Root 化端末・エミュレータでの実行時検証は未実施です。
- サーバー側のデバイス整合性判定は検証していません。
- パッチ版は公式署名と異なるため、公式アプリのデータは引き継げません。
