import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../services/overlay_service.dart';

/// Faz 0 fizibilite ekranı: izinler, deney parametreleri, başlat/durdur.
class PrototypeScreen extends StatefulWidget {
  const PrototypeScreen({super.key, required this.service, this.screenshotBuilder});

  final OverlayService service;

  /// Verilirse AppBar'da "ekran görüntüsü çevir" (iOS modu) kısayolu gösterilir.
  final WidgetBuilder? screenshotBuilder;

  @override
  State<PrototypeScreen> createState() => _PrototypeScreenState();
}

class _PrototypeScreenState extends State<PrototypeScreen> with WidgetsBindingObserver {
  OverlayStatus _status = const OverlayStatus.unknown();
  CaptureConfig _config = const CaptureConfig();
  String? _error;
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
    // Servis bildirimden de durdurulabildiği için durumu düzenli yokla.
    _poll = Timer.periodic(const Duration(seconds: 1), (_) => _refresh());
  }

  @override
  void dispose() {
    _poll?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    try {
      final status = await widget.service.status();
      if (mounted) setState(() => _status = status);
    } on PlatformException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() => _error = null);
    try {
      await action();
    } on PlatformException catch (e) {
      setState(() => _error = e.message ?? e.code);
    }
    await _refresh();
  }

  Future<void> _start() => _run(() async {
        final ok = await widget.service.start(_config);
        if (!ok && mounted) setState(() => _error = 'Ekran yakalama onayı verilmedi');
      });

  @override
  Widget build(BuildContext context) {
    final running = _status.running;
    return Scaffold(
      appBar: AppBar(
        title: const Text('MSR · Faz 0 prototip'),
        actions: [
          if (widget.screenshotBuilder case final builder?)
            IconButton(
              key: const Key('openScreenshot'),
              tooltip: 'Ekran görüntüsü çevir',
              icon: const Icon(Icons.image_search),
              onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: builder)),
            ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _StatusCard(running: running),
          const SizedBox(height: 16),
          Text('İzinler', style: Theme.of(context).textTheme.titleMedium),
          _PermissionTile(
            title: 'Diğer uygulamaların üstünde göster',
            granted: _status.overlayPermission,
            onRequest: () => _run(widget.service.requestOverlayPermission),
          ),
          _PermissionTile(
            title: 'Bildirimler (servis bildirimi için)',
            granted: _status.notificationPermission,
            onRequest: () => _run(widget.service.requestNotificationPermission),
          ),
          const Divider(height: 32),
          Text('Deney parametreleri', style: Theme.of(context).textTheme.titleMedium),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('FLAG_SECURE overlay'),
            subtitle: const Text('Açık: kendi çevirimiz yakalamada siyah görünür'),
            value: _config.secureOverlay,
            onChanged: running ? null : (v) => setState(() => _config = _config.copyWith(secureOverlay: v)),
          ),
          _SliderTile(
            label: 'Tarama aralığı: ${_config.intervalMs} ms',
            value: _config.intervalMs.toDouble(),
            min: 200,
            max: 3000,
            divisions: 28,
            onChanged: running ? null : (v) => setState(() => _config = _config.copyWith(intervalMs: v.round())),
          ),
          _SliderTile(
            label: 'Yakalama çözünürlüğü: %${(_config.captureScale * 100).round()}',
            value: _config.captureScale,
            min: 0.25,
            max: 1,
            divisions: 3,
            onChanged: running ? null : (v) => setState(() => _config = _config.copyWith(captureScale: v)),
          ),
          if (running)
            const Padding(
              padding: EdgeInsets.only(top: 4),
              child: Text('Parametreleri değiştirmek için önce durdurun.'),
            ),
          const SizedBox(height: 24),
          FilledButton.icon(
            key: const Key('toggle'),
            onPressed: running
                ? () => _run(widget.service.stop)
                : (_status.overlayPermission ? _start : null),
            icon: Icon(running ? Icons.stop : Icons.play_arrow),
            label: Text(running ? 'Durdur' : 'Başlat'),
          ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ),
        ],
      ),
    );
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.running});

  final bool running;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      color: running ? scheme.primaryContainer : scheme.surfaceContainerHighest,
      child: ListTile(
        leading: Icon(running ? Icons.translate : Icons.translate_outlined),
        title: Text(running ? 'Çalışıyor' : 'Kapalı'),
        subtitle: Text(running
            ? 'Ölçümler ekranın sol üstündeki yeşil şeritte'
            : 'İzinleri verin, sonra Başlat'),
      ),
    );
  }
}

class _PermissionTile extends StatelessWidget {
  const _PermissionTile({required this.title, required this.granted, required this.onRequest});

  final String title;
  final bool granted;
  final VoidCallback onRequest;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Icon(granted ? Icons.check_circle : Icons.error_outline,
          color: granted ? Colors.green : Theme.of(context).colorScheme.error),
      title: Text(title),
      trailing: granted ? null : TextButton(onPressed: onRequest, child: const Text('İzin ver')),
    );
  }
}

class _SliderTile extends StatelessWidget {
  const _SliderTile({
    required this.label,
    required this.value,
    required this.min,
    required this.max,
    required this.divisions,
    required this.onChanged,
  });

  final String label;
  final double value;
  final double min;
  final double max;
  final int divisions;
  final ValueChanged<double>? onChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label),
        Slider(value: value, min: min, max: max, divisions: divisions, onChanged: onChanged),
      ],
    );
  }
}
