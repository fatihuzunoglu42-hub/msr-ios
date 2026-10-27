# MSR: Telefonda Şeffaf Ekran Çevirici

> Bu dosya Claude Code için hazırlanmıştır. Projenin amacını, kapsamını, kararlarını ve geliştirme sırasını içerir. Kararlar kullanıcı (Fatih) ile birlikte alınmıştır. Belirsiz kalan yerler "AÇIK SORU" olarak işaretlidir.

---

## 1. Amaç

Kullanıcı, İngilizce bir uygulamayı (örneğin DoorDash) telefonunda kullanırken, ekranın üstünde şeffaf bir katmanın İngilizce yazıları otomatik olarak Türkçe'ye çevirmesini istiyor.

- Alt katman: Orijinal uygulama (DoorDash, Uber vb.). Normal şekilde kullanılabilir.
- Üst katman: Şeffaf overlay. Ekrandaki metni OCR ile okur, çevirir, gösterir.
- Kamera kullanılmaz. Çalışma tamamen ekran içeriği üzerinden yapılır.
- Tüm uygulamalarda genel olarak çalışır (uygulamaya özel değil).

## 2. Platform ve Test

- Hedef platformlar: Android ve iOS
- Yayın: Google Play Store ve Apple App Store
- Test cihazları: Geliştiricinin kendi Android telefonu ve kendi iPhone'u

## 3. Teknoloji Kararları

| Konu | Karar | Not |
|------|-------|-----|
| Uygulama çatısı | Flutter | Android ve iOS için tek kod tabanı |
| OCR | Google ML Kit (Text Recognition) | Cihaz üzerinde çalışır, Türkçe ve İngilizce destekler |
| Çeviri (online) | Google Translate (Cloud Translation API) | İnternet gerekir |
| Çeviri (offline) | Google ML Kit Translation (on-device) | Dil modelleri indirilir. Genel "Google Translate offline" API'si yoktur. |
| Android yerel kod | Kotlin | Overlay ve ekran erişimi için |
| iOS yerel kod | Swift | Overlay ve ekran erişimi için |

**Önemli teknik notlar:**
- Android'de başka uygulamaların üstüne çizim `SYSTEM_ALERT_WINDOW` ile yapılır. Ekran metnini okumak için bir Erişilebilirlik Servisi (AccessibilityService) gerekir. Ekran görüntüsü için MediaProjection kullanılır.
- iOS'ta başka uygulamaların ekranını okumak ve üstüne sürekli overlay koymak sistem tarafından sınırlıdır. Üçüncü taraf uygulamaların içeriğini okuyan bir Erişilebilirlik API'si yoktur. Bu yüzden iOS tarafında tam olarak aynı deneyim mümkün olmayabilir. **AÇIK SORU:** iOS için hangi yaklaşım kabul edilebilir? (ör. ReplayKit ile ekran yayını, ya da iOS'ta sınırlı mod)
- Dokunma geçişi (touch passthrough): Overlay'de boş alanlar dokunmayı alttaki uygulamaya iletmelidir. Bu Android'de `FLAG_NOT_TOUCHABLE` / `FLAG_NOT_FOCUSABLE` ile sağlanır.

## 4. Temel Özellikler

### 4.1 Overlay ve Çeviri Akışı
1. Kullanıcı MSR'ı etkinleştirir.
2. Overlay açılır. Dokunuşlar otomatik olarak alttaki uygulamaya geçer.
3. Ekran görüntüsü alınır ve OCR ile metin tespit edilir.
4. Tespit edilen metin, seçili kaynak dilden hedef dile çevrilir (varsayılan: İngilizce → Türkçe).
5. Çeviri, kullanıcının seçtiği görüntüleme modunda gösterilir.
6. Yeni metin algılandığında güncelleme gerçek zamanlı yapılır.

### 4.2 Görüntüleme Modları (kullanıcı seçer)
- **Yerine yaz:** Orijinal metnin üstüne çeviriyi koyar.
- **Ayrı panel:** Ekranın üstünde ya da altında çevirileri toplayan bir kutu gösterir.
- **İkisine geçiş:** Kullanıcı ayarlardan modu değiştirebilir.

### 4.3 Çevirinin Görünümü (kullanıcı özelleştirir)
- Yazı tipi, boyut, yazı rengi
- Arka plan: şeffaf / yarı opak / opak
- Varsayılan stil kullanıcı tarafından seçilir (sabit bir varsayılan dayatılmaz)

### 4.4 Online ve Offline
- Varsayılan mod: Kullanıcı ayarlardan seçer (Online / Offline / Otomatik).
- Offline modda çeviri dil modelleri önceden indirilmiş olmalıdır.
- Offline aktifken ekranın **üst köşesinde "Offline" göstergesi** görünür.

### 4.5 Dil Ayarları
- Kaynak dil ve hedef dil ayarlardan seçilir.
- Kaynak ve hedef dil **hem onboarding'de hem ayarlarda** seçilebilir.
- Açılışta dil otomatik seçilmez, kullanıcı manuel seçer.
- Açıkken dil hızlıca değiştirilebilir: hem ayarlar menüsünden hem hızlı ayarlar panelinden.

### 4.6 Aktivasyon (hepsi açık, kullanıcı seçer)
- Uygulama simgesinden
- Hızlı ayarlar paneli (bildirim paneli) butonundan
- Jest ile (ör. iki parmakla aşağı kaydırma)
- Ses komutu ("Çeviriyi aç / kapat")
- Hangilerinin açık olacağı ayarlardan seçilir.

### 4.7 Performans ve Pil
- **Pil tasarrufu modu** (kullanıcı açar veya kapatır):
  - OCR güncelleme sıklığı düşer (ör. 2–3 saniyede bir)
  - Küçük yazılar atlanır
  - Ekran parlaklığı azaltılır (overlay içinde yarı saydam koyu katman)
- **Gerçek zamanlı çeviri** varsayılandır (pil modu kapalıyken).
- Sıcaklık ve pil uyarısı: Kullanıcı kendi yönetir. Ekranın bir köşesinde **küçük, kesintisiz bir mesaj** gösterilir. Otomatik kapanma veya otomatik performans düşürme yoktur.

### 4.8 Onboarding (ilk açılış)
Kullanıcı iki seçenekten birini seçer:
- **Kısa özet (2 adım):** 1) MSR nedir ve nasıl çalışır 2) Hızlı kurulum (izinler ve dil seçimi)
- **Ayrıntılı rehber (5 adım):** 1) MSR nedir 2) Overlay nasıl çalışır 3) OCR ve çeviri 4) Offline mod 5) Ayarlar ve kişiselleştirme

### 4.9 Ana Ekran
- Overlay aç/kapat düğmesi
- Durum göstergesi: Çalışıyor / Kapalı / Offline
- Ayarlar butonu
- Hızlı dil değiştirme

### 4.10 Ayarlar Ekranı
- Dil: kaynak, hedef
- Görünüm: yazı tipi, boyut, renk, arka plan, görüntüleme modu
- Performans: pil tasarrufu ve alt seçenekleri
- Bağlantı: Online / Offline / Otomatik, dil modeli indir/sil
- Aktivasyon: simge, hızlı ayarlar, jest, ses komutu (açık/kapalı)

### 4.11 Dokunma Geçişi
- Otomatik geçiş. Kullanıcı overlay'i ayrıca kapatmak zorunda değil.
- Overlay'deki çeviri kutusuna dokunulursa işlem alttaki uygulamaya geçer. Çeviri kutusu sadece görüntüleme amaçlıdır.

## 5. İzinler

**Android:**
- `SYSTEM_ALERT_WINDOW` (üstte çizim)
- Erişilebilirlik Servisi (ekran içeriği)
- Ekran yakalama izni (MediaProjection)
- `INTERNET` (online çeviri)
- Depolama: dil modelleri için (Android sürümüne göre uygun izin kullanılır, Scoped Storage)
- Mikrofon: sadece ses komutu açıksa istenir

**iOS:**
- Ekran kaydı / yayın izni (ReplayKit) — **AÇIK SORU**, iOS yaklaşımı netleşince
- `NSMicrophoneUsageDescription` (ses komutu açıksa)
- Yerel ağ gerekmez (çeviri internet üzerinden yapılır)

## 6. Geliştirme Fazları

Zaman planı yapılmayacak. Sıra önemlidir.

- **Faz 1:** Flutter projesi, Android overlay, dokunma geçişi, ekran yakalama
- **Faz 2:** ML Kit OCR entegrasyonu, metin kutularının konumlandırılması
- **Faz 3:** Google Translate online çeviri
- **Faz 4:** ML Kit offline çeviri, dil modeli indirme/silme, offline göstergesi
- **Faz 5:** Görüntüleme modları, stil özelleştirme, ayarlar ekranı
- **Faz 6:** Onboarding (kısa ve ayrıntılı), ana ekran, hızlı ayarlar paneli
- **Faz 7:** Pil tasarrufu modu, sıcaklık/pil uyarısı
- **Faz 8:** iOS tarafı (yukarıdaki kısıtlar netleştikten sonra)
- **Faz 9:** Test (Android ve iOS cihazlar), hata düzeltme, yayın

## 7. Proje Yapısı (öneri)

```
msr/
├── lib/
│   ├── main.dart
│   ├── screens/        onboarding, home, settings
│   ├── services/       ocr_service, translation_service, overlay_service, battery_service, storage_service
│   ├── models/         settings_model, ocr_result, translation_result
│   └── utils/          constants, theme
├── android/
│   └── app/src/main/kotlin/.../   OverlayService, AccessibilityService, MediaProjection
├── ios/                iOS tarafı (Faz 8)
└── test/
```

## 8. Kapsam Dışı (şimdilik)

- Geçmiş çevirilerin kaydı (history). Her seferinde temiz başlanır.
- Log, istatistik, kullanım verisi toplanmaz.
- Kamera ile çeviri yapılmaz.
- Otomatik performans düşürme veya otomatik kapanma yoktur.
- Sesli geri bildirim yoktur.

## 9. Açık Sorular

1. iOS'ta hangi yaklaşım kabul edilir? (Tam eşdeğer deneyim sistem kısıtları nedeniyle mümkün olmayabilir.)
2. Google Translate'in ücretli kullanım sınırları ve maliyet kabulü.
3. Offline dil paketleri kaç dil için indirilecek? Başlangıç: İngilizce → Türkçe.

## 10. Kaynak Notları

- Bu dosya, Fatih ile yapılan planlama konuşmasının özetidir.
- Paket adları ve sürümleri geliştirme başında doğrulanmalıdır. Önceki bir taslakta yer alan bazı Flutter paket adları güvenilir değildi.
