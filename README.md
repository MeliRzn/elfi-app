# Elfaris Watch · Android

Aplicativo Android nativo do Elfaris Watch. O cliente Android é mantido neste repositório, separado do bot.

## Primeiro acesso

1. No WhatsApp da Elfi, envie `cadastro` no privado.
2. O bot envia um link pessoal de ativação.
3. Abra o Elfaris Watch e cole o link completo no campo **Adicionar link de ativação**.
4. Toque em **Vincular minha conta**. O aplicativo identifica o servidor, ativa a conta e mantém a sessão salva no aparelho.

O comando `cadastro` não cria contas duplicadas: o número do WhatsApp é único. Se a conta já existir, o link enviado recupera o acesso e cria uma nova sessão para a mesma conta.

Para atualizar o endereço do servidor após a troca do túnel, use `transmitir app` no WhatsApp e abra o link de conexão no aparelho.

## Build

O GitHub Actions compila automaticamente o APK Debug em cada push na branch `main`.

- Android minSdk 21
- Java 17
- Gradle 8.9
- ABIs: armeabi-v7a e arm64-v8a

O APK fica em **Actions → Elfaris Watch Android → Artifacts → elfaris-watch-debug**.

## Recursos

- Ativação e recuperação de acesso por link pessoal do WhatsApp.
- Sessão persistente e perfil com avatar.
- Interface em tema escuro, com telas de entrada em layout único.
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
