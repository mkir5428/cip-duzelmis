import json
import os
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

import voice_server


class GuvenliHesapTest(unittest.TestCase):
    def test_turkce_islem_kelimelerini_hesaplar(self):
        temiz, sonuc = voice_server.guvenli_hesap("12 çarpı 4")
        self.assertEqual(temiz, "12 * 4")
        self.assertEqual(sonuc, 48)

    def test_islem_onceligini_korur(self):
        _, sonuc = voice_server.guvenli_hesap("(5 artı 3) bölü 2")
        self.assertEqual(sonuc, 4)

    def test_buyuk_usleri_reddeder(self):
        with self.assertRaises(ValueError):
            voice_server.guvenli_hesap("2**101")

    def test_uzun_ifadeyi_reddeder(self):
        with self.assertRaises(ValueError):
            voice_server.guvenli_hesap("1" * 201)

    def test_karmasik_ifadeyi_reddeder(self):
        with self.assertRaises(ValueError):
            voice_server.guvenli_hesap("2**(3**100)")


class AsistanYetenekTest(unittest.TestCase):
    @staticmethod
    def text_response(text):
        return SimpleNamespace(
            stop_reason="end_turn",
            content=[SimpleNamespace(type="text", text=text)],
        )

    def test_detayli_cevaba_yeterli_token_ve_guvenli_araclar_sunar(self):
        messages = SimpleNamespace(create=Mock(return_value=self.text_response("Ayrıntılı yanıt.")))
        with patch("voice_server.claude_istemci", return_value=SimpleNamespace(messages=messages)):
            response = voice_server.claude_cevap("Ayrıntılı anlat.")

        self.assertEqual(response, "Ayrıntılı yanıt.")
        call = messages.create.call_args.kwargs
        self.assertGreater(call["max_tokens"], 350)
        self.assertEqual(
            {tool["name"] for tool in call["tools"]},
            {"guvenli_hesapla", "hava_durumu"},
        )
        self.assertIn("Sabit bir cümle sınırına takılma", call["system"])
        self.assertIn("bir yapay zeka olduğunu dürüstçe söyle", call["system"])

    def test_hesap_araci_sonucunu_sentez_icin_modele_iletir(self):
        messages = SimpleNamespace(create=Mock(side_effect=[
            SimpleNamespace(
                stop_reason="tool_use",
                content=[SimpleNamespace(
                    type="tool_use",
                    id="call-1",
                    name="guvenli_hesapla",
                    input={"ifade": "12 çarpı 4"},
                )],
            ),
            self.text_response("12 çarpı 4, 48 eder."),
        ]))
        with patch("voice_server.claude_istemci", return_value=SimpleNamespace(messages=messages)):
            response = voice_server.claude_cevap("12 çarpı 4 kaç?")

        self.assertEqual(response, "12 çarpı 4, 48 eder.")
        self.assertEqual(messages.create.call_count, 2)
        follow_up = messages.create.call_args.kwargs["messages"]
        self.assertEqual(follow_up[-1]["role"], "user")
        self.assertEqual(follow_up[-1]["content"], [{
            "type": "tool_result",
            "tool_use_id": "call-1",
            "content": "12 * 4 = 48",
        }])

    def test_hava_araci_sorulan_yeri_kullanir(self):
        messages = SimpleNamespace(create=Mock(side_effect=[
            SimpleNamespace(
                stop_reason="tool_use",
                content=[SimpleNamespace(
                    type="tool_use",
                    id="weather-1",
                    name="hava_durumu",
                    input={"sehir": "Ankara"},
                )],
            ),
            self.text_response("Ankara bugün açık."),
        ]))
        with (
            patch("voice_server.claude_istemci", return_value=SimpleNamespace(messages=messages)),
            patch("voice_server.hava_cek", return_value="Ankara şu an 12 derece.") as weather,
        ):
            response = voice_server.claude_cevap("Ankara'da hava nasıl?")

        self.assertEqual(response, "Ankara bugün açık.")
        weather.assert_called_once_with("Ankara")
        self.assertEqual(
            messages.create.call_args.kwargs["messages"][-1]["content"][0]["content"],
            "Ankara şu an 12 derece.",
        )

    def test_arac_girdileri_sinirlanir_ve_hatada_hassas_detay_donus_yapmaz(self):
        self.assertEqual(
            voice_server._asistan_aracini_calistir(
                "hava_durumu", {"sehir": "x" * 101},
            ),
            "Şehir adı geçersiz.",
        )
        with (
            patch("voice_server.claude_istemci", side_effect=RuntimeError("secret provider detail")),
            patch.object(voice_server.log, "error") as log_error,
        ):
            response = voice_server.isle_komut("Merhaba")[0]

        self.assertEqual(response, "Şu an yanıt oluşturamadım. Biraz sonra tekrar dener misin?")
        log_error.assert_called_once()
        self.assertEqual(
            voice_server._asistan_aracini_calistir(
                "guvenli_hesapla", {"ifade": "1 bölü 0"},
            ),
            "Bu ifadeyi güvenli biçimde hesaplayamadım.",
        )

    def test_bilinmeyen_arac_cagrisi_yurutulmez(self):
        messages = SimpleNamespace(create=Mock(return_value=SimpleNamespace(
            stop_reason="tool_use",
            content=[SimpleNamespace(
                type="tool_use",
                id="unexpected-1",
                name="dosya_sil",
                input={"path": "important.txt"},
            )],
        )))
        with patch("voice_server.claude_istemci", return_value=SimpleNamespace(messages=messages)):
            response = voice_server.claude_cevap("Dosyaya bak.")
        self.assertEqual(response, "İstediğin bilgiyi şu anda tamamlayamadım; tekrar deneyebilir misin?")
        messages.create.assert_called_once()


class KimlikDogrulamaTest(unittest.TestCase):
    def setUp(self):
        self.client = voice_server.app.test_client()

    def test_saglik_sayfasi_api_anahtarsiz_acik(self):
        with patch.dict(os.environ, {"ASISTAN_API_KEY": ""}):
            response = self.client.get("/")
        self.assertEqual(response.status_code, 200)

    def test_anahtar_yapilandirilmadiysa_api_503_doner(self):
        with patch.dict(os.environ, {"ASISTAN_API_KEY": ""}):
            response = self.client.get("/komut?text=merhaba")
        self.assertEqual(response.status_code, 503)

    def test_anahtar_olmadan_api_401_doner(self):
        with patch.dict(os.environ, {"ASISTAN_API_KEY": "test-key"}):
            response = self.client.get("/komut?text=merhaba")
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.headers["WWW-Authenticate"], "Bearer")

    def test_gecerli_anahtarla_komut_calisir(self):
        with (
            patch.dict(os.environ, {"ASISTAN_API_KEY": "test-key"}),
            patch("voice_server.isle_komut", return_value=("Tamam.", 200, {})),
        ):
            response = self.client.get(
                "/komut?text=merhaba",
                headers={"Authorization": "Bearer test-key"},
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_data(as_text=True), "Tamam.")

    def test_android_gecmis_mesajlarini_sunucu_islecinden_bagimsiz_gonderir(self):
        history = [
            {"role": "user", "content": "Benim adım Deniz"},
            {"role": "assistant", "content": "Memnun oldum."},
        ]
        with (
            patch.dict(os.environ, {"ASISTAN_API_KEY": "test-key"}),
            patch("voice_server.isle_komut", return_value=("Selam Deniz.", 200, {})) as command,
        ):
            response = self.client.post(
                "/komut",
                data={
                    "text": "Adım neydi?",
                    "session": "mobile-session",
                    "gecmis": json.dumps(history, ensure_ascii=False),
                },
                headers={"Authorization": "Bearer test-key"},
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(command.call_args.args[1], history)

    def test_ceviri_form_postunu_destekler(self):
        metin = "Merhaba " * 499 + "Merhaba"
        with (
            patch.dict(os.environ, {"ASISTAN_API_KEY": "test-key"}),
            patch("voice_server.claude_sistem_gorevi", return_value="Hello") as gorev,
        ):
            response = self.client.post(
                "/cevir",
                data={"text": metin, "dil": "ingilizce"},
                headers={"Authorization": "Bearer test-key"},
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_data(as_text=True), "Hello")
        self.assertTrue(gorev.call_args.args[1].endswith(metin))

    def test_istek_boyutu_sinirlidir(self):
        self.assertEqual(voice_server.app.config["MAX_CONTENT_LENGTH"], 25_000_000)


class GroqTest(unittest.TestCase):
    def test_transkripsiyon_bearer_anahtari_gonderir(self):
        yanit = Mock()
        yanit.json.return_value = {"text": "merhaba"}
        with (
            patch.dict(os.environ, {"GROQ_API_KEY": "groq-test"}),
            patch("voice_server.requests.post", return_value=yanit) as post,
        ):
            sonuc = voice_server.groq_transkript(b"ses", "ses.m4a")

        self.assertEqual(sonuc, "merhaba")
        self.assertEqual(
            post.call_args.kwargs["headers"]["Authorization"],
            "Bearer groq-test",
        )


if __name__ == "__main__":
    unittest.main()
