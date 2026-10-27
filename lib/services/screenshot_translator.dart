import 'dart:io';

import 'package:flutter/painting.dart';
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';
import 'package:google_mlkit_translation/google_mlkit_translation.dart';

/// Ekran görüntüsünde bulunan ve çevrilen tek metin bloğu (görüntü pikseli koordinatları).
class TranslatedBlock {
  const TranslatedBlock({required this.box, required this.source, required this.text});

  final Rect box;
  final String source;
  final String text;
}

class ScreenshotTranslation {
  const ScreenshotTranslation({
    required this.imageSize,
    required this.blocks,
    required this.ocrTime,
    required this.translateTime,
  });

  final Size imageSize;
  final List<TranslatedBlock> blocks;
  final Duration ocrTime;
  final Duration translateTime;
}

/// Fiyat, saat gibi harf içermeyen metinler çevrilmez (Android'deki BoxTracker ile aynı kural).
bool isTranslatable(String text) => RegExp(r'\p{L}', unicode: true).hasMatch(text);

/// Bir görüntü dosyasındaki metni okuyup çevirir. Testlerde sahte uygulama verilir.
abstract class ScreenshotTranslator {
  /// Çeviri modelleri cihazda yoksa indirir (ilk kullanımda).
  Future<void> ensureModels();

  Future<ScreenshotTranslation> translateFile(String path);

  Future<void> dispose();
}

/// ML Kit ile tamamen cihaz üzerinde OCR + EN→TR çeviri.
class MlKitScreenshotTranslator implements ScreenshotTranslator {
  final _recognizer = TextRecognizer(script: TextRecognitionScript.latin);
  final _translator = OnDeviceTranslator(
    sourceLanguage: TranslateLanguage.english,
    targetLanguage: TranslateLanguage.turkish,
  );
  final _models = OnDeviceTranslatorModelManager();
  final _cache = <String, String>{};

  @override
  Future<void> ensureModels() async {
    for (final code in [TranslateLanguage.english.bcpCode, TranslateLanguage.turkish.bcpCode]) {
      if (!await _models.isModelDownloaded(code)) {
        // Mobil veride de çalışsın; model dil başına ~30 MB.
        await _models.downloadModel(code, isWifiRequired: false);
      }
    }
  }

  @override
  Future<ScreenshotTranslation> translateFile(String path) async {
    final image = await decodeImageFromList(await File(path).readAsBytes());
    final imageSize = Size(image.width.toDouble(), image.height.toDouble());
    image.dispose();

    final ocrWatch = Stopwatch()..start();
    final recognized = await _recognizer.processImage(InputImage.fromFilePath(path));
    ocrWatch.stop();

    final translateWatch = Stopwatch()..start();
    final blocks = <TranslatedBlock>[];
    for (final block in recognized.blocks) {
      final source = block.text.replaceAll('\n', ' ').trim();
      if (!isTranslatable(source)) continue;
      final text = _cache[source] ??= await _translator.translateText(source);
      blocks.add(TranslatedBlock(box: block.boundingBox, source: source, text: text));
    }
    translateWatch.stop();

    return ScreenshotTranslation(
      imageSize: imageSize,
      blocks: blocks,
      ocrTime: ocrWatch.elapsed,
      translateTime: translateWatch.elapsed,
    );
  }

  @override
  Future<void> dispose() async {
    await _recognizer.close();
    await _translator.close();
  }
}
