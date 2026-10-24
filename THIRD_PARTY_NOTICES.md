# Third-party notices

## Wildercord

- Source: https://github.com/defnotean/wildercord
- Version: tag `v0.13.0-alpha` (2026-10-15), commit `9f5a21fe7964097492cd92ac64e4c4e05ef5236a`
- License: MIT (below)

The magic fx core adapts Wildercord's client code. These files start with
`// Adapted from Wildercord (MIT) https://github.com/defnotean/wildercord @9f5a21fe7964097492cd92ac64e4c4e05ef5236a`:

| Our file (`mod/src/client/java/local/carter/office/`) | Adapted from (`src/client/java/dev/wildercord/client/`) |
| --- | --- |
| `client/magic/fx/LightPipeline.java` | `fx/GlowLayers.java` (additive `BlendFunction.LIGHTNING` and subtractive pipelines, OIT set), `fx/SigilGroup.java` (extent-culled particle group) |
| `client/magic/fx/MagicCircleRenderer.java` | `fx/SpellCircleParticle.java` (circles drawn as pieces of a line texture: `piece`, `ring`, `line`, `tiles`, polygons) |
| `client/magic/fx/CircleParticle.java` | `fx/SpellCircleParticle.java` (the circle as a particle: fade, opening, extent) |
| `client/magic/fx/ChargeCircles.java` | `fx/ChargeCircles.java` (a circle per charging player, made from their synced use state) |
| `client/magic/fx/BoltParticle.java` | `fx/Facing.java` (`along`: a piece of line turned to face the camera) |
| `client/magic/fx/Shake.java` | `fx/ScreenEffects.java` (decaying shake, FOV kick curve, Screen Effect Scale) |
| `client/magic/fx/HitStop.java`, `mixin/client/HitStopMixin.java` | `fx/HitStop.java`, `mixin/EntityRenderDispatcherHitStopMixin.java` |
| `client/magic/fx/ShaderCompat.java` | `compat/ShaderCompat.java`, `mixin/QuadParticleRenderStateMixin.java` (magic on vanilla's translucent layer under a shader pack) |
| `client/magic/fx/wind/WindRibbon.java` | `fx/Facing.java` (`along`, via our `BoltParticle`: ribbon pieces turned to face the camera) |

No Wildercord textures or other assets are included: the fx textures are drawn by `tools/make_fx_textures.py`.

```
MIT License

Copyright (c) 2026 defnotean

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
