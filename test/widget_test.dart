import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:msr/main.dart';
import 'package:msr/services/overlay_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('msr/control');
  final calls = <MethodCall>[];
  var running = false;
  var overlayPermission = true;

  setUp(() {
    calls.clear();
    running = false;
    overlayPermission = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'status':
          return {'overlayPermission': overlayPermission, 'notificationPermission': true, 'running': running};
        case 'start':
          running = true;
          return true;
        case 'stop':
          running = false;
          return null;
      }
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  Future<void> pumpApp(WidgetTester tester) async {
    // Telefon boyutu (~392x873 mantıksal piksel).
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 2.75;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MsrApp(service: OverlayService()));
    await tester.pump();
  }

  testWidgets('Başlat varsayılan parametrelerle servisi başlatır', (tester) async {
    await pumpApp(tester);
    expect(find.text('Kapalı'), findsOneWidget);

    await tester.tap(find.byKey(const Key('toggle')));
    await tester.pump();

    final start = calls.singleWhere((c) => c.method == 'start');
    expect(start.arguments, {'secureOverlay': true, 'intervalMs': 700, 'captureScale': 0.5});
    expect(find.text('Çalışıyor'), findsOneWidget);
    expect(find.text('Durdur'), findsOneWidget);

    await tester.pumpWidget(const SizedBox()); // periyodik zamanlayıcıyı kapat
  });

  testWidgets('Overlay izni yoksa Başlat devre dışı', (tester) async {
    overlayPermission = false;
    await pumpApp(tester);

    final button = tester.widget<ButtonStyleButton>(find.byKey(const Key('toggle')));
    expect(button.onPressed, isNull);
    expect(find.text('İzin ver'), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
  });

  test('CaptureConfig.copyWith yalnız verilen alanı değiştirir', () {
    final c = const CaptureConfig().copyWith(intervalMs: 1500);
    expect(c.toMap(), {'secureOverlay': true, 'intervalMs': 1500, 'captureScale': 0.5});
  });
}
