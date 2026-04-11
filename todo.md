# TODO

- [ ] Allow removal of pending videos
- [ ] Add a GIF icon to animated GIFs in pending videos
- [x] Accept shared GIFs from other apps (AndroidManifest share intent-filters only match `video/*`; add `image/gif`)
- [x] Show GIFs in the in-app media picker (`MainScreen.kt` uses `PickVisualMedia.VideoOnly` and `type = "video/*"` — needs to also accept `image/gif`)
- [ ] Use separate Firebase projects for dev and release (debug build currently piggybacks on the release `google-services.json` by duplicating the client entry with package `com.davotoula.lce.dev`; should be a real dev Firebase project with its own app id/api key so analytics and crash reports don't mingle)
