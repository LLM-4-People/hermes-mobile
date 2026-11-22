# Hermes Mobile

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Native Android views, with Java and the Android SDK. This project starts with an empty repository and connects to a separately hosted Hermes Agent.

## Users and operating context

People use their own protected Hermes profile to chat, write code, and return to long-running work from a phone. A phone can hold more than one account. The people who own those accounts can have different private workspaces and provider credentials.

## Product purpose

Resume a conversation, see what happened while away, and act on the work in the same thread. Coding sessions need readable code and diffs, visible tool activity, and approval requests. Artifacts belong to their originating conversation.

## Capabilities and constraints

The APK speaks to the Hermes API. Sessions, jobs, model inventory and available tools come from the connected runtime. Show the source and scope of each usage metric. An unavailable cost or timing measurement needs an explicit unknown state.

A user can receive a private alert while the app is closed and unlock the owning account before opening its thread. Protect different people with separately isolated server runtimes. An agent can propose changes to the workspace in the conversation where the need arises. The user can preview, apply, and undo them.

## Product principles

- Keep the transcript and composer within reach during ongoing work.
- Preserve the user's place across disconnects, incoming events and account switches.
- Keep the entire French interface usable with the keyboard visible, TalkBack, larger text and light or dark appearance.
- Make changes to tools, providers, reminders and layouts explicit and observable.
