# MSR – Ekran Çevirici

Telefonda açık olan herhangi bir uygulamanın ekran metnini OCR ile okuyup şeffaf bir katmanda çeviren Flutter uygulaması.

- Proje dokümanı: [docs/MSR-Proje-Dokumantasyon.md](docs/MSR-Proje-Dokumantasyon.md)
- Yol planı: [docs/yol-plani.md](docs/yol-plani.md)

## Durum
Faz 0 – fizibilite prototipi (Android). Yakalama/OCR/çeviri döngüsü native Kotlin'de:
`android/app/src/main/kotlin/com/msrcevirici/msr/`

## Çalıştırma
```
flutter pub get
flutter test                               # Dart testleri
cd android && gradlew testDebugUnitTest    # Kotlin birim testleri
flutter run                                # USB ile bağlı Android telefonda
```
