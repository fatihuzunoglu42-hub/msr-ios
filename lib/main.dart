import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'screens/prototype_screen.dart';
import 'screens/screenshot_screen.dart';
import 'services/overlay_service.dart';
import 'services/screenshot_translator.dart';

void main() {
  runApp(MsrApp(service: OverlayService()));
}

class MsrApp extends StatelessWidget {
  const MsrApp({super.key, required this.service, this.translatorFactory});

  final OverlayService service;
  final ScreenshotTranslator Function()? translatorFactory;

  @override
  Widget build(BuildContext context) {
    final factory = translatorFactory ?? MlKitScreenshotTranslator.new;
    return MaterialApp(
      title: 'MSR',
      theme: ThemeData(colorSchemeSeed: Colors.teal, useMaterial3: true),
      darkTheme: ThemeData(colorSchemeSeed: Colors.teal, brightness: Brightness.dark, useMaterial3: true),
      // iOS başka uygulamaların üstüne overlay'e izin vermez; orada ekran görüntüsü modu açılır.
      home: defaultTargetPlatform == TargetPlatform.iOS
          ? ScreenshotScreen(translatorFactory: factory)
          : PrototypeScreen(
              service: service,
              screenshotBuilder: (_) => ScreenshotScreen(translatorFactory: factory),
            ),
    );
  }
}
