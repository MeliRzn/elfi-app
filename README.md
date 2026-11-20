# Elfaris Watch · Android

Aplicativo Android nativo do Elfaris Watch.

## Build
O GitHub Actions compila automaticamente o APK Debug em cada push na branch `main`.

- Android minSdk 21
- Java 17
- Gradle 8.9
- ABIs: armeabi-v7a e arm64-v8a

O APK fica em **Actions → Elfaris Watch Android → Artifacts → elfaris-watch-debug**.

## Recursos
- Ativação da conta por link enviado pelo WhatsApp.
- Sessão persistente e perfil com avatar.
- Entrada em salas por link de convidado.
- Captura de tela nativa usando MediaProjection.
- Publicação WebRTC com áudio opcional do microfone.
- Espectadores podem assistir pelo site.

## Limitações atuais
- Áudio interno de outros aplicativos Android ainda não é capturado.
- Reprodução nativa de salas (modo espectador dentro do app) ainda está em desenvolvimento.
- Integração LiveKit Self-hosted ainda não foi concluída.
- O backend do bot precisa estar atualizado e acessível por HTTPS.

Não publique tokens, credenciais ou arquivos `.env` neste repositório.
