import 'package:flutter/services.dart';

/// Native yakalama/overlay servisinin anlık durumu.
class OverlayStatus {
  const OverlayStatus({
    required this.overlayPermission,
    required this.notificationPermission,
    required this.running,
  });

  const OverlayStatus.unknown()
      : overlayPermission = false,
        notificationPermission = false,
        running = false;

  factory OverlayStatus.fromMap(Map<Object?, Object?> map) => OverlayStatus(
        overlayPermission: map['overlayPermission'] == true,
        notificationPermission: map['notificationPermission'] == true,
        running: map['running'] == true,
      );

  final bool overlayPermission;
  final bool notificationPermission;
  final bool running;
}

/// Faz 0 deney parametreleri.
class CaptureConfig {
  const CaptureConfig({
    this.secureOverlay = true,
    this.intervalMs = 700,
    this.captureScale = 0.5,
  });

  /// Overlay penceresine FLAG_SECURE: yakalamada siyah görünür, kendi çevirimiz OCR'lanmaz.
  final bool secureOverlay;
  final int intervalMs;

  /// Yakalama çözünürlüğü (ekranın oranı). Düşük = hızlı, küçük yazıda zayıf.
  final double captureScale;

  CaptureConfig copyWith({bool? secureOverlay, int? intervalMs, double? captureScale}) =>
      CaptureConfig(
        secureOverlay: secureOverlay ?? this.secureOverlay,
        intervalMs: intervalMs ?? this.intervalMs,
        captureScale: captureScale ?? this.captureScale,
      );

  Map<String, Object> toMap() => {
        'secureOverlay': secureOverlay,
        'intervalMs': intervalMs,
        'captureScale': captureScale,
      };
}

/// Android tarafındaki `msr/control` kanalının sarmalayıcısı.
class OverlayService {
  OverlayService({MethodChannel? channel}) : _channel = channel ?? const MethodChannel('msr/control');

  final MethodChannel _channel;

  Future<OverlayStatus> status() async {
    final map = await _channel.invokeMapMethod<Object?, Object?>('status');
    return map == null ? const OverlayStatus.unknown() : OverlayStatus.fromMap(map);
  }

  Future<void> requestOverlayPermission() => _channel.invokeMethod('requestOverlayPermission');

  Future<void> requestNotificationPermission() => _channel.invokeMethod('requestNotificationPermission');

  /// Ekran yakalama onayını ister ve servisi başlatır. Kullanıcı reddederse false.
  Future<bool> start(CaptureConfig config) async =>
      await _channel.invokeMethod<bool>('start', config.toMap()) ?? false;

  Future<void> stop() => _channel.invokeMethod('stop');
}
