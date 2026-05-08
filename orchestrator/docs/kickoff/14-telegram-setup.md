# Telegram Setup — Manual Steps for Mike

These steps must be completed before the M6 deployment can use the notification system. They take about 5 minutes.

The notification system itself is built at M6. This document is the operational handoff for the credentials.

## Steps

1. Open Telegram. Search for `@BotFather`.
2. Start a chat with BotFather. Send `/newbot`. Follow prompts to name your bot (suggested: "Meridian PM").
3. BotFather returns the bot token. Save it securely — this is a credential.
4. Open `.env` in the Orchestrator working directory. Add:

   ```
   TELEGRAM_BOT_TOKEN=your-bot-token-here
   ```

5. Open Telegram, find your new bot, send any message to it (e.g., `test`). This starts the conversation so the bot can see your chat.
6. In a browser, visit:

   ```
   https://api.telegram.org/bot<YOUR_TOKEN>/getUpdates
   ```

   Replace `<YOUR_TOKEN>` with the token from step 3.
7. In the JSON response, find the `chat.id` field. It will be a number like `123456789`.
8. Add to `.env`:

   ```
   TELEGRAM_CHAT_ID=123456789
   ```

9. Restart the Orchestrator service.
10. Test from an interactive Claude Code session with PM:

    > "PM, send a test notification with urgency `info`, subject 'Setup test', body 'Notification service online.'"

    PM should call `notify_human` with those parameters. You should receive the message on Telegram within a few seconds.

## Troubleshooting

If anything fails, check:

- Bot token typed correctly (no extra whitespace, no quotes)
- Chat ID is a number, not a string
- `.env` file is in the correct directory (Orchestrator working directory)
- Orchestrator service was restarted after `.env` changes
- `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` are visible in service startup logs (with values redacted to first/last 4 chars)
- The service log shows `event=notification.enabled` at startup; if it shows `event=notification.disabled` with a reason, fix that reason

## Security notes

- **Bot token is a credential.** Anyone holding it can send messages as your bot. Treat it like a password — no commits, no chat pastes, no screenshots.
- **`.env` is `.gitignore`'d.** Verify it does not appear in `git status` after editing. If it ever does, stop, rotate the token, and clean history before pushing.
- **Rotate if exposed.** If the bot token leaks (committed by accident, pasted somewhere, screenshotted in a shared session), revoke it via BotFather (`/revoke`) and reissue. Then update `.env` and restart the service.
- **Single-chat-id assumption.** Phase 1 is hardcoded to one `TELEGRAM_CHAT_ID`. There is no fallback, no broadcast list, no secondary recipient. If that chat becomes unreachable (bot conversation deleted, Telegram account access lost, new device that hasn't started the bot), outbound messages queue indefinitely with `delivered=0`. The dashboard surfaces the backlog if it exceeds 1 hour. Recovery is manual: update `TELEGRAM_CHAT_ID` in `.env` and restart the service. Phase 2 may add multi-recipient support; Phase 1 deliberately does not.
- The chat id itself is not a credential, but it is an identifier tying messages to your specific Telegram account; treat it as private.

## What this enables

Once setup is complete:

- PM can notify you on your phone for foundational decisions, downgrade decisions, end-of-session reports (`info`), questions blocking work (`question`), and urgent escalations (`urgent`).
- You can reply via Telegram. PM consumes replies on next session start via `get_unread_replies`.
- All inbound and outbound messages are persisted in SQLite (`inbound_messages`, `outbound_messages`) and queryable via `get_message_history`.
- Engineer, Implementer, and Tester have no notification access. They surface upward through the existing task chain only.
