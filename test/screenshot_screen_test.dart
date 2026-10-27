import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:msr/main.dart';
import 'package:msr/screens/screenshot_screen.dart';
import 'package:msr/services/overlay_service.dart';
import 'package:msr/services/screenshot_translator.dart';

class FakeTranslator implements ScreenshotTranslator {
  // Tembel: setUp'ta değil test gövdesinde (FakeAsync bölgesinde) oluşsun, yoksa pump ilerletemez.
  late final modelGate = Completer<void>();
  final calls = <String>[];
  bool disposed = false;
  Object? failWith;

  @override
  Future<void> ensureModels() {
    calls.add('ensureModels');
    return modelGate.future;
  }

  @override
  Future<ScreenshotTranslation> translateFile(String path) async {
    calls.add('translate:$path');
    if (failWith != null) throw failWith!;
    return const ScreenshotTranslation(
      imageSize: Size(1080, 2400),
      blocks: [TranslatedBlock(box: Rect.fromLTWH(10, 20, 300, 40), source: 'Place order', text: 'Sipariş ver')],
      ocrTime: Duration(milliseconds: 120),
      translateTime: Duration(milliseconds: 45),
    );
  }

  @override
  Future<void> dispose() async => disposed = true;
}

void main() {
  late FakeTranslator translator;

  setUp(() => translator = FakeTranslator());

  /// Seçim → model → çeviri zincirindeki await'lerin tamamlanması için birkaç kare ilerletir.
  Future<void> settle(WidgetTester tester) async {
    for (var i = 0; i < 5; i++) {
      await tester.pump();
    }
  }

  Future<void> pumpScreen(WidgetTester tester, {String? pickResult = '/tmp/shot.png'}) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 2.75;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(
      home: ScreenshotScreen(translatorFactory: () => translator, pickImage: () async => pickResult),
    ));
  }

  testWidgets('Görüntü seçilince model hazırlanır, çevrilir ve süreler gösterilir', (tester) async {
    await pumpScreen(tester);
    expect(find.text('Ekran görüntüsü seç'), findsOneWidget);

    await tester.tap(find.byKey(const Key('pick')));
    await tester.pump();
    expect(find.textContaining('Çeviri modeli hazırlanıyor'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byKey(const Key('pick'))).onPressed, isNull);

    translator.modelGate.complete();
    await settle(tester);

    expect(translator.calls, ['ensureModels', 'translate:/tmp/shot.png']);
    expect(find.text('OCR 120 ms · Çeviri 45 ms · 1 blok'), findsOneWidget);
    expect(find.text('Başka görüntü seç'), findsOneWidget);
    expect(find.byType(CustomPaint), findsWidgets);

    // Orijinal/çeviri geçişi
    await tester.tap(find.byKey(const Key('toggleOriginal')));
    await tester.pump();
    expect(find.byTooltip('Çeviriyi göster'), findsOneWidget);
  });

  testWidgets('Seçim iptal edilirse hiçbir şey yapılmaz', (tester) async {
    await pumpScreen(tester, pickResult: null);
    await tester.tap(find.byKey(const Key('pick')));
    await tester.pump();
    expect(translator.calls, isEmpty);
  });

  testWidgets('Çeviri hatası kullanıcıya gösterilir', (tester) async {
    translator.failWith = Exception('ML Kit yok');
    translator.modelGate.complete();
    await pumpScreen(tester);
    await tester.tap(find.byKey(const Key('pick')));
    await settle(tester);
    expect(find.textContaining('Çevrilemedi'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byKey(const Key('pick'))).onPressed, isNotNull);
  });

  testWidgets('Ekran kapanınca çevirmen serbest bırakılır', (tester) async {
    await pumpScreen(tester);
    await tester.pumpWidget(const SizedBox());
    expect(translator.disposed, isTrue);
  });

  testWidgets("iOS'ta ana ekran ekran görüntüsü modudur", (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    await tester.pumpWidget(MsrApp(service: OverlayService(), translatorFactory: () => translator));
    expect(find.byType(ScreenshotScreen), findsOneWidget);
    debugDefaultTargetPlatformOverride = null;
  });

  test('isTranslatable harf arar (Türkçe dahil)', () {
    expect(isTranslatable('Sipariş'), isTrue);
    expect(isTranslatable('ğ'), isTrue);
    expect(isTranslatable(r'$12.99 — 4.5'), isFalse);
  });

  test('scaleRect koordinatları ölçekler', () {
    expect(scaleRect(const Rect.fromLTRB(100, 200, 300, 400), 0.5), const Rect.fromLTRB(50, 100, 150, 200));
  });
}
