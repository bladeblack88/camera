# Tuya Camera Player

Отдельное Android-приложение в модуле `tuya-player`. Воспроизводит RTSP на Android с помощью AndroidX Media3 RTSP — без VLC, HLS, MediaMTX и компьютера.

## Камера
- IP: `192.168.0.151`
- RTSP port: `9554`
- ONVIF port: `835`
- Предзаполненный пример URL: `rtsp://admin:12345@192.168.0.151:9554/`

**Важно:** пароль в исходном коде и APK можно извлечь. Не распространяйте APK с этим паролем; лучше сменить пароль и вводить его в приложении. Точный RTSP path зависит от модели. Вставьте полный Stream URI из Onvier (Profile1 → Stream URI), если адрес по умолчанию не заработает.

## Сборка
GitHub Actions собирает отдельный APK задачей `assembleTuyaPlayerDebug`. Артефакт: `tuya-player-debug.apk`.
