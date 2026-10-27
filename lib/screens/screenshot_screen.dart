import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../services/screenshot_translator.dart';

/// Galeriden görüntü seçer; testlerde sahte uygulama verilir. Seçim iptal edilirse null.
typedef ImagePick = Future<String?> Function();

Future<String?> pickFromGallery() async {
  final file = await ImagePicker().pickImage(source: ImageSource.gallery, requestFullMetadata: false);
  return file?.path;
}

/// iOS modu: seçilen ekran görüntüsündeki metni çevirip görüntünün üstüne yazar.
class ScreenshotScreen extends StatefulWidget {
  const ScreenshotScreen({super.key, required this.translatorFactory, this.pickImage = pickFromGallery});

  final ScreenshotTranslator Function() translatorFactory;
  final ImagePick pickImage;

  @override
  State<ScreenshotScreen> createState() => _ScreenshotScreenState();
}

class _ScreenshotScreenState extends State<ScreenshotScreen> {
  late final ScreenshotTranslator _translator = widget.translatorFactory();
  String? _imagePath;
  ScreenshotTranslation? _result;
  String? _busy;
  String? _error;
  bool _showOriginal = false;

  @override
  void dispose() {
    _translator.dispose();
    super.dispose();
  }

  Future<void> _pick() async {
    final path = await widget.pickImage();
    if (path == null || !mounted) return;
    setState(() {
      _imagePath = path;
      _result = null;
      _error = null;
      _showOriginal = false;
      _busy = 'Çeviri modeli hazırlanıyor (ilk seferde indirilir)…';
    });
    try {
      await _translator.ensureModels();
      if (!mounted) return;
      setState(() => _busy = 'Metin okunuyor ve çevriliyor…');
      final result = await _translator.translateFile(path);
      if (mounted) setState(() => _result = result);
    } catch (e) {
      if (mounted) setState(() => _error = 'Çevrilemedi: $e');
    } finally {
      if (mounted) setState(() => _busy = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    final result = _result;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Ekran görüntüsü çevir'),
        actions: [
          if (result != null)
            IconButton(
              key: const Key('toggleOriginal'),
              tooltip: _showOriginal ? 'Çeviriyi göster' : 'Orijinali göster',
              icon: Icon(_showOriginal ? Icons.translate : Icons.visibility),
              onPressed: () => setState(() => _showOriginal = !_showOriginal),
            ),
        ],
      ),
      body: Column(
        children: [
          Expanded(child: _buildPreview(result)),
          if (_busy != null) ...[
            const LinearProgressIndicator(),
            Padding(padding: const EdgeInsets.all(8), child: Text(_busy!)),
          ],
          if (_error != null)
            Padding(
              padding: const EdgeInsets.all(8),
              child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ),
          if (result != null)
            Padding(
              padding: const EdgeInsets.all(8),
              child: Text(
                'OCR ${result.ocrTime.inMilliseconds} ms · Çeviri ${result.translateTime.inMilliseconds} ms · '
                '${result.blocks.length} blok',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
              child: SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  key: const Key('pick'),
                  onPressed: _busy == null ? _pick : null,
                  icon: const Icon(Icons.photo_library),
                  label: Text(_imagePath == null ? 'Ekran görüntüsü seç' : 'Başka görüntü seç'),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPreview(ScreenshotTranslation? result) {
    final path = _imagePath;
    if (path == null) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(32),
          child: Text(
            'Çevirmek istediğiniz uygulamanın ekran görüntüsünü alın, sonra buradan seçin. '
            'Metin cihaz üzerinde okunur ve Türkçe\'ye çevrilir.',
            textAlign: TextAlign.center,
          ),
        ),
      );
    }
    final image = Image.file(
      File(path),
      fit: BoxFit.fill,
      errorBuilder: (_, _, _) => const ColoredBox(color: Colors.black12),
    );
    if (result == null) return Center(child: image);
    return InteractiveViewer(
      maxScale: 5,
      child: Center(
        child: AspectRatio(
          aspectRatio: result.imageSize.width / result.imageSize.height,
          child: LayoutBuilder(
            builder: (context, constraints) {
              final scale = constraints.maxWidth / result.imageSize.width;
              return Stack(
                fit: StackFit.expand,
                children: [
                  image,
                  if (!_showOriginal)
                    CustomPaint(painter: TranslationPainter(blocks: result.blocks, scale: scale)),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}

/// Görüntü koordinatlarındaki kutuyu ekrandaki önizleme ölçeğine çevirir.
Rect scaleRect(Rect r, double scale) =>
    Rect.fromLTRB(r.left * scale, r.top * scale, r.right * scale, r.bottom * scale);

/// Çevirileri orijinal metnin üstüne, kutuya sığacak yazı boyutuyla çizer.
class TranslationPainter extends CustomPainter {
  TranslationPainter({required this.blocks, required this.scale});

  final List<TranslatedBlock> blocks;
  final double scale;

  static const _minFont = 7.0;
  static const _maxFont = 22.0;
  static const _padding = 2.0;

  @override
  void paint(Canvas canvas, Size size) {
    final bg = Paint()..color = const Color(0xE6181A1E);
    for (final block in blocks) {
      final rect = scaleRect(block.box, scale);
      final innerWidth = math.max(1.0, rect.width - 2 * _padding);
      var font = (rect.height * 0.75).clamp(_minFont, _maxFont);
      var painter = _layout(block.text, font, innerWidth);
      while (painter.height > rect.height - 2 * _padding && font > _minFont) {
        font = math.max(_minFont, font * 0.9);
        painter = _layout(block.text, font, innerWidth);
      }
      // Sığmadıysa kutuyu aşağı uzat; metni kesmektense taşırmak daha okunur.
      final height = math.max(rect.height, painter.height + 2 * _padding);
      canvas.drawRRect(
        RRect.fromRectAndRadius(Rect.fromLTWH(rect.left, rect.top, rect.width, height), const Radius.circular(3)),
        bg,
      );
      painter.paint(canvas, Offset(rect.left + _padding, rect.top + _padding));
    }
  }

  TextPainter _layout(String text, double font, double width) => TextPainter(
        text: TextSpan(text: text, style: TextStyle(color: Colors.white, fontSize: font, height: 1.1)),
        textDirection: TextDirection.ltr,
      )..layout(maxWidth: width);

  @override
  bool shouldRepaint(TranslationPainter old) => old.blocks != blocks || old.scale != scale;
}
