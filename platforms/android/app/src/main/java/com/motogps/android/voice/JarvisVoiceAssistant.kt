package com.motogps.android.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.motogps.android.viewmodel.NavigationViewModel
import java.util.Locale

class JarvisVoiceAssistant(
    private val context: Context,
    private val viewModel: NavigationViewModel,
    private val getGatewayUrl: () -> String
) : TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isListening = false

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.ITALIAN)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
            } else {
                Log.e("JarvisVoice", "Lingua italiana non supportata per TTS")
            }
        } else {
            Log.e("JarvisVoice", "Inizializzazione TTS fallita ($status)")
        }
    }

    fun startListening() {
        if (isListening) return

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            val msg = "Riconoscimento vocale non disponibile su questo dispositivo."
            viewModel.updateJarvisState("ERRORE", msg)
            speak(msg)
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        viewModel.updateJarvisState("ASCOLTO...", "In ascolto, parla ora...")
                    }

                    override fun onBeginningOfSpeech() {
                        viewModel.updateJarvisState("ASCOLTO...", "Rilevamento voce...")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        isListening = false
                        viewModel.updateJarvisState("ELABORAZIONE...", "Elaborazione in corso...")
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "Nessun comando rilevato."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tempo di ascolto scaduto."
                            SpeechRecognizer.ERROR_AUDIO -> "Errore registrazione audio."
                            else -> "Errore riconoscimento vocale ($error)."
                        }
                        viewModel.updateJarvisState("TOCCA PER PARLARE", errorMsg)
                        speak(errorMsg)
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val recognizedText = matches[0]
                            viewModel.updateJarvisState("ELABORAZIONE...", recognizedText)
                            processVoiceCommand(recognizedText)
                        } else {
                            viewModel.updateJarvisState("TOCCA PER PARLARE", "Nessun comando riconosciuto")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            viewModel.updateJarvisState("ASCOLTO...", matches[0])
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "it-IT")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            Log.e("JarvisVoice", "Errore avvio ascolto vocale", e)
            viewModel.updateJarvisState("ERRORE", "Impossibile avviare il microfono")
        }
    }

    fun stopListening() {
        if (isListening) {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e("JarvisVoice", "Errore stop listening", e)
            }
            isListening = false
            viewModel.updateJarvisState("TOCCA PER PARLARE", "Ascolto interrotto")
        }
    }

    fun speak(text: String) {
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_tts_id")
        }
    }

    fun processVoiceCommand(rawQuery: String) {
        val query = rawQuery.trim().lowercase(Locale.ITALIAN)

        when {
            // Comando 1: Navigation / Routing
            query.startsWith("portami a ") || query.startsWith("portami in ") ||
            query.startsWith("naviga verso ") || query.startsWith("naviga a ") ||
            query.startsWith("vai a ") || query.startsWith("vai in ") -> {
                val destination = query
                    .replace("^portami (a|in) ".toRegex(), "")
                    .replace("^naviga (verso|a) ".toRegex(), "")
                    .replace("^vai (a|in) ".toRegex(), "")
                    .trim()

                if (destination.isNotBlank()) {
                    viewModel.updateJarvisState("ELABORAZIONE...", "Ricerca $destination...")
                    viewModel.searchAndNavigate(destination, getGatewayUrl()) { success, placeName ->
                        val resp = if (success) {
                            "Rotta calcolata verso $placeName. Avvio navigazione."
                        } else {
                            "Impossibile trovare la destinazione $destination."
                        }
                        viewModel.updateJarvisState("NAVIGAZIONE", resp)
                        speak(resp)
                    }
                } else {
                    val resp = "Per favore, specifica una destinazione."
                    viewModel.updateJarvisState("TOCCA PER PARLARE", resp)
                    speak(resp)
                }
            }

            // Comando 2: Trip Status
            query.contains("quanti chilometri mancano") || query.contains("quanto manca") ||
            query.contains("stato viaggio") || query.contains("stato del viaggio") ||
            query.contains("distanza rimanente") -> {
                val summary = viewModel.getNavStatusSummary()
                viewModel.updateJarvisState("STATO VIAGGIO", summary)
                speak(summary)
            }

            // Comando 3: Stop Navigation
            query.contains("ferma navigazione") || query.contains("annulla percorso") ||
            query.contains("interrompi navigazione") || query.contains("stop navigazione") -> {
                viewModel.stopNavigation()
                val resp = "Navigazione terminata."
                viewModel.updateJarvisState("TERMINATO", resp)
                speak(resp)
            }

            // Comando 4: Download Offline Map
            query.contains("scarica mappa") || query.contains("salva corridoio") ||
            query.contains("scarica la mappa") || query.contains("mappa offline") -> {
                viewModel.downloadCurrentArea(context)
                val resp = "Avvio download della mappa in corso."
                viewModel.updateJarvisState("DOWNLOAD", resp)
                speak(resp)
            }

            // Fallback
            else -> {
                val resp = "Comando non riconosciuto: $rawQuery"
                viewModel.updateJarvisState("TOCCA PER PARLARE", resp)
                speak(resp)
            }
        }
    }

    fun destroy() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e("JarvisVoice", "Errore destroy JarvisVoiceAssistant", e)
        }
    }
}
