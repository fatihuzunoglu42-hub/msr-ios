# MSR – Profesyonel Yol Planı

## Context
MSR, herhangi bir uygulamanın üstünde şeffaf katmanla ekran metnini OCR + çeviri ile gösteren bir Flutter uygulaması. Elde yalnızca `MSR-Proje-Dokumantasyon.md` var (kod yok). Hedef: Android + iOS, Play Store + App Store. Bu plan dokümanı inceler, teknik/politika risklerini ortaya çıkarır, mimariyi ve kapılı (gate'li) fazları tanımlar.

---

## 1. Doküman İncelemesi – Düzeltilmesi Gereken Noktalar

| # | Bulgu | Öneri |
|---|-------|-------|
| 1 | **AccessibilityService gereksiz ve Play riski yüksek.** OCR zaten ekran görüntüsünden metin çıkarıyor. Google Play, erişilebilirlik API'sini yalnızca erişilebilirlik amaçlı kullanımlara izinli tutar; "çeviri aracı" için ret/askıya alma riski var. | Birincil yol: **MediaProjection + OCR**. AccessibilityService'i çıkar (jest/ses için de buna bağımlı olma). İstenirse sonra isteğe bağlı eklenir. |
| 2 | **Geri besleme döngüsü:** Ekran görüntüsü kendi overlay'imizi de yakalar → çeviriyi tekrar OCR'lar. Dokümanda yok. | Yakalama anında overlay'i gizle / OCR sonucunu kendi kutularımızla maskele / kutu bazlı önbellek. Faz 0'da çözülmeli. |
| 3 | **Android 14+ MediaProjection:** Her oturumda onay, `mediaProjection` türünde foreground service ve kalıcı bildirim zorunlu. Sessiz/kalıcı yakalama yok. | UX'i buna göre tasarla (açarken onay, bildirimden durdur). |
| 4 | **Cloud Translation API anahtarı** uygulamaya gömülürse çalınır, maliyet patlar. | Ücretsiz/offline ML Kit'i varsayılan yap; online için kendi küçük proxy'n (kota + anahtar sunucuda) ya da kullanıcının kendi anahtarı. Maliyet kabulü AÇIK SORU #2. |
| 5 | **"Veri toplanmaz" ile bulut çevirisi çelişir.** Ekran içeriği (ödeme, mesaj) Google'a gider. | Gizlilik politikası + Play Data Safety formu + ilk kullanımda açık rıza. Online modda şifre alanları/`FLAG_SECURE` içerik zaten yakalanmaz; belirtilmeli. |
| 6 | **Jest (iki parmak kaydırma) ve ses komutu (sürekli dinleme)** erişilebilirlik/arka plan mikrofonu gerektirir; Android 14+ kısıtlı, pil yer. | MVP'den çıkar. MVP aktivasyon: uygulama simgesi + Hızlı Ayarlar Tile (`TileService`) + yüzen küçük buton. Jest/ses "v2". |
| 7 | **Ağır döngü Flutter'da olmamalı.** Servis Flutter engine'i olmadan çalışabilmeli; ML Kit Flutter eklentileri dosya/byte tabanlı, yavaş. | Yakalama→OCR→çeviri→çizim döngüsü **native Kotlin** (ML Kit Android SDK doğrudan), Flutter yalnız UI/ayarlar. Köprü: MethodChannel/EventChannel + paylaşılan ayar deposu. |
| 8 | **OCR dil kapsamı:** ML Kit Latin modeli İngilizce/Türkçe için yeterli; Çince/Japonca/Korece/Devanagari ayrı modeller. | MVP: Latin. Dil listesi genişlemesi sonra. ML Kit Language ID ile otomatik algılama opsiyonu düşünülebilir (dokümanda "otomatik seçilmez" kararı korunur, varsayılan kapalı). |
| 9 | **iOS gerçekçi değerlendirme:** Başka uygulamanın üstüne overlay yok. ReplayKit Broadcast Extension: ~50 MB bellek sınırı, kullanıcı her seferinde başlatır, çıktıyı uygulama üstüne çizemez. | Bkz. §4 – iOS ayrı bir "yoldaş mod" ürünü. Android-first yayın. |
| 10 | **Mac/ücret:** iOS derlemesi için Mac veya bulut CI (Codemagic vb.) gerekir; geliştirici Windows kullanıyor. Play $25 (tek sefer), Apple $99/yıl. | Faz 8 öncesi karar. |
| 11 | Hızlı ayar panelinden "hızlı dil değiştirme", pil tasarrufunda "parlaklık düşürme" gibi maddeler uygulanabilir ama düşük öncelik. | Fazlarda geç sıraya al. |
| 12 | Paket adı/sürümleri doğrulanmamış (dokümanın kendi notu). | Faz 0 başında pub.dev + resmi dokümanlardan doğrula. |

---

## 2. Hedef Mimari

```
Flutter (Dart)                      Native Android (Kotlin)
 ├─ Onboarding / Ana ekran           ├─ ForegroundService (mediaProjection)
 ├─ Ayarlar (Riverpod/Provider)      ├─ CaptureEngine  (ImageReader, düşük çözünürlük)
 ├─ Model indir/sil UI               ├─ FrameDiffer    (değişmeyen kareyi atla)
 └─ MethodChannel/EventChannel ───►  ├─ OcrEngine      (ML Kit, sadece değişen bölge)
                                     ├─ TranslationEngine (ML Kit offline | Cloud proxy) + LRU önbellek
                                     ├─ OverlayRenderer (WindowManager, FLAG_NOT_TOUCHABLE/NOT_FOCUSABLE, Canvas)
                                     ├─ QuickSettingsTile
                                     └─ SettingsStore (SharedPreferences/DataStore)
```

Performans boru hattı: ~1–2 fps yakalama → 0.5x ölçek → karo-hash ile değişim tespiti → yalnız değişen bölgede OCR → satırları bloklara birleştir → önbellekte yoksa toplu çeviri → çiz. Kaydırma algılanınca kutuları gizle/yeniden hizala.

---

## 3. Fazlar (kapılı – her faz çıkış kriteri geçilmeden sonrakine geçilmez)

### Faz 0 – Fizibilite Prototipi (YENİ, en riskli kısım önce)
Çıktı: Kotlin tek-ekran prototip: MediaProjection ile yakala → ML Kit OCR → kutu çiz (çeviri sahte). DoorDash/Uber üzerinde dene.
Çıkış kriteri: overlay döngüsü (geri besleme dahil) çalışıyor; uçtan uca gecikme ölçüldü (hedef < ~1,5 sn); 10 dk kullanımda pil/ısı ölçüldü.
Karar noktası: Başarısızsa mimari/kapsam yeniden değerlendirilir.

### Faz 1 – Proje İskeleti
Flutter projesi, klasör yapısı (doküman §7), lint/format, CI (build + analyze + test), Android native modül iskeleti, kanal sözleşmesi.

### Faz 2 – Çekirdek Android Hattı
Foreground service, dokunma geçişi, yakalama, OCR, konumlandırma, kare farkı, önbellek. (Eski Faz 1+2.)

### Faz 3 – Çeviri
ML Kit offline en→tr (+ model indir/sil, "Offline" göstergesi), sonra online çeviri (proxy) ve Online/Offline/Otomatik mantığı. (Eski Faz 3+4.)

### Faz 4 – Görünüm ve Ayarlar
"Yerine yaz" ve "ayrı panel" modları, yazı tipi/boyut/renk/arka plan, ayarlar ekranı, ayarların canlı servise yansıması.

### Faz 5 – Kullanıcı Akışı
Onboarding (2 adımlı kısa / 5 adımlı ayrıntılı), izin akışları (overlay, ekran yakalama, bildirim), ana ekran, Quick Settings Tile, yüzen buton.

### Faz 6 – Pil/Sıcaklık
Pil tasarrufu modu (düşük fps, küçük yazı atlama, karartma katmanı), köşe uyarı mesajı (sıcaklık/pil — `BatteryManager`, `PowerManager.thermalStatus`).

### Faz 7 – Android Kalite ve Yayın Hazırlığı
Cihaz matrisi testi (Android 10–15, farklı üreticiler: Samsung/Xiaomi arka plan kısıtları), performans profili, gizlilik politikası, Data Safety, mağaza varlıkları, kapalı test (Play zorunlu test süresini doğrula), **Android yayını**.

### Faz 8 – iOS (ayrı karar kapısı, §4)
### Faz 9 – v2 Adayları
Jest, ses komutu, ek diller, otomatik dil algılama, isteğe bağlı erişilebilirlik modu.

---

## 4. iOS Seçenekleri (AÇIK SORU #1 – Öneri)
| Seçenek | Gerçekçilik | Not |
|---|---|---|
| A. Android'e eşdeğer sürekli overlay | **Mümkün değil** | iOS izin vermiyor |
| B. ReplayKit Broadcast Extension + sonucu PiP/Live Activity/bildirimle göstermek | Zor, App Review riski, 50 MB bellek | Çeviri üstte değil, ayrı pencerede |
| C. Ekran görüntüsü çeviri: paylaş/Action Extension veya Back Tap ile "Ekran görüntüsünü çevir" → uygulama içinde yerine-yaz sonucu | **En gerçekçi, düşük risk** | Gerçek zamanlı değil, tek seferlik |

**Öneri:** Android'i bitir; iOS'ta önce **C**, talep varsa **B**. Pazarlamada "iOS'ta sınırlı mod" açıkça belirtilir.

---

## 5. Risk Tablosu
| Risk | Etki | Azaltma |
|---|---|---|
| Play politika reddi (erişilebilirlik/ekran yakalama) | Yüksek | AccessibilityService kullanma; ekran yakalama amacını gizlilik politikasında açıkla |
| Gecikme/titreme (kaydırmada) | Yüksek | Faz 0'da ölç; kare farkı + önbellek |
| Pil/ısı | Orta | Faz 6; fps sınırı |
| Üretici arka plan öldürmesi | Orta | Foreground service + pil muafiyeti rehberi |
| Çeviri API maliyeti | Orta | Offline varsayılan, önbellek, proxy kotası |
| iOS kapsam beklentisi | Orta | §4 kararı erken ver |
| DoorDash vb. uygulamaların `FLAG_SECURE` kullanması | Düşük–Orta | Yakalanamaz içerik için kullanıcıya bilgi ver |

---

## 6. Doğrulama (her fazda)
- Birim testleri: ayar modeli, birleştirme/konumlandırma mantığı, önbellek (Dart `flutter test`; Kotlin JUnit).
- Gerçek cihaz senaryosu: Android telefonda DoorDash/Uber açık → MSR aç → metinler Türkçe, alttaki uygulama normal dokunuyor.
- Ölçümler: uçtan uca gecikme, CPU, 10 dk pil düşüşü, sıcaklık (Battery Historian/`adb dumpsys batterystats`).
- Çevrimdışı test: uçak modunda en→tr çalışıyor, "Offline" göstergesi görünüyor.
- Yayın öncesi: Play Pre-launch report, izin akışı ilk kurulum + yeniden kurulum testi.

## 7. Başlamadan Gereken Kararlar (varsayılan önerilerle)
1. iOS: **Önce Android, iOS'ta ekran görüntüsü modu** (öneri).
2. Online çeviri: **Faz 3'te karar; MVP offline-first** (öneri).
3. Jest + ses komutu: **v2'ye ertele** (öneri).
4. AccessibilityService: **kullanma** (öneri).
5. Mac erişimi/bulut CI: Faz 8 öncesi.

## 7b. Doğrulama Sonuçları (2026-10-26, resmi kaynaklar)
| İddia | Sonuç | Düzeltme |
|---|---|---|
| Play, AccessibilityService'i çeviri için reddeder | **Kısmen doğru.** Yasak değil; ama `isAccessibilityTool` yalnız engelli kullanıcılara yönelik araçlara açık. Diğerleri Play Console beyanı + belirgin açıklama/rıza zorunlu; aldatıcı kullanım → askıya alma/hesap kapatma. | Öneri değişmez: kullanma (gereksiz yük + risk). |
| Android 14+ MediaProjection her oturumda onay | **Doğru.** Oturum = tek `createVirtualDisplay()`; token tek kullanımlık. Sıra: onay → `mediaProjection` FGS başlat → `getMediaProjection()`; yanlış sıra SecurityException. | Faz 0/2'de bu sıra uygulanacak. |
| ML Kit OCR Türkçe | **Doğru.** v2 Latin, Türkçe "Supported" kademesinde. | — |
| ML Kit çeviri en→tr | **Doğru.** `tr` destekli. Model boyutu sayfada yok (cihazda ölçülecek). | — |
| ReplayKit ~50 MB | **Yaygın bildirilen, Apple resmi belgesinde bulunamadı.** | iOS B seçeneği riskli olarak kalır. |
| Play kapalı test | **Doğru:** 13 Kas 2023 sonrası kişisel hesaplar: ≥12 test kullanıcısı, kesintisiz 14 gün; aksi halde Production kilitli. | Faz 7'ye 14+ gün ve 12 test kullanıcısı bulma eklendi. |
| Overlay'in yakalamaya girmesi | **Doğru:** overlay yakalanır; overlay'e özel dışlama API'si yok. | Aday çözüm: overlay penceresine `FLAG_SECURE` (yakalamada siyah görünür, OCR atlar). Faz 0'da test edilecek. |

## 8. Hemen Sonraki Adım (onay sonrası)
Faz 0'ı başlat: Flutter SDK + Android Studio kurulumunu doğrula, Kotlin prototip, paket/SDK sürümlerini resmi kaynaklardan doğrula.
