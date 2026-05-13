# Third-Party Notices

## RikkaHub-derived Code

This private research prototype directly copies selected `ai` and `common` modules from `C:\VCP\Eric\rikkahub` to accelerate Android LLM client exploration.

The source project declares a segmented dual license with AGPLv3 obligations for qualifying open-source/non-commercial use and a commercial license path for other use cases. Before making UniVCP public, distributing it broadly, or using it commercially, either:

- replace the copied code with a clean-room implementation, or
- obtain a compatible commercial license from the RikkaHub maintainer, or
- comply fully with the applicable AGPLv3 obligations.

Two copied upstream unit-test classes are excluded from the default `:ai:testDebugUnitTest` task because they use brittle reflection over provider internals that are not part of UniVCP's first research milestone. The copied production code still compiles, and UniVCP should add focused OpenAI-compatible streaming tests as the prototype stabilizes.

## UniStudy Renderer Assets

Renderer vendor files are copied from the local UniStudy desktop project under `C:\VCP\Eric\edu_chat_project\vendor` for offline prototype rendering.
