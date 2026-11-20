# Elfaris Watch · Android

Aplicativo Android nativo do Elfaris Watch. O cliente Android é mantido neste repositório, separado do bot.

## Primeiro acesso

1. No WhatsApp da Elfi, envie `cadastro` no privado.
2. O bot envia um link pessoal de ativação.
3. Abra o Elfaris Watch e cole o link completo no campo **Adicionar link de ativação**.
4. Toque em **Vincular minha conta**. O aplicativo identifica o servidor, ativa a conta e mantém a sessão salva no aparelho.

O comando `cadastro` não cria contas duplicadas: o número do WhatsApp é único. Se a conta já existir, o link enviado recupera o acesso e cria uma nova sessão para a mesma conta.

A origem do servidor é configurada pelo link de ativação enviado com `cadastro`. Para criar uma sala compartilhável, use `transmitir app` no WhatsApp e cole o link da sala no aplicativo.

## Build

O GitHub Actions compila automaticamente o APK Debug em cada push na branch `main`.

- Android minSdk 21
- Java 17
- Gradle 8.9
- ABIs solicitadas ao Gradle: `armeabi-v7a` (ARMv7 32-bit) e `arm64-v8a` (ARM64).
- A CI inspeciona o APK final e falha se qualquer uma dessas arquiteturas estiver ausente nas bibliotecas nativas.
- ARMv6 (`armeabi`) não é incluído pela configuração atual e não pode ser considerado compatível com o WebRTC nativo atual. Só anunciar suporte após existir uma dependência WebRTC compilada para ARMv6 e validar o app em aparelho real.

O APK fica em **Actions → Elfaris Watch Android → Artifacts → elfaris-watch-debug**.

## Recursos

- Ativação e recuperação de acesso por link pessoal do WhatsApp.
- Sessão persistente e perfil com avatar.
- Interface em tema escuro, com telas de entrada em layout único.
- Entrada em salas por link compartilhável, com transmissão Android P2P direta para cada espectador.
- Captura de tela nativa usando MediaProjection.
- Publicação WebRTC com áudio opcional do microfone.
- Espectadores podem assistir pelo site.

## Limitações atuais

- Áudio interno de outros aplicativos Android ainda não é capturado.
- Reprodução nativa de salas (modo espectador dentro do app) ainda está em desenvolvimento.
- Integração LiveKit Self-hosted ainda não foi concluída.
- O backend do bot precisa estar atualizado e acessível por HTTPS.

Não publique tokens, credenciais ou arquivos `.env` neste repositório.
