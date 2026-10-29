package com.anon.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.*;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.*;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.stream.Collectors;

public class AnonClient implements ClientModInitializer {
    public static ModuleManager MODULE_MANAGER;
    public static KeyBinding OPEN_GUI;
    public static final Set<String> FRIENDS = new HashSet<>();

    @Override
    public void onInitializeClient() {
        MODULE_MANAGER = new ModuleManager();
        MODULE_MANAGER.init();
        OPEN_GUI = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.anon.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_0, "category.anon"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_GUI.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new ClickGUI());
            }
            MODULE_MANAGER.onTick();
        });

        HudRenderCallback.EVENT.register((ctx, delta) -> MODULE_MANAGER.onRender2D(ctx, delta));
        WorldRenderEvents.AFTER_ENTITIES.register(ctx ->
                MODULE_MANAGER.onRender3D(ctx.matrixStack(), ctx.tickDelta()));
    }
}

enum Category { COMBAT, MOVEMENT, UTILITY, RENDER, CLIENT }

abstract class Setting {
    public final String name;
    protected Setting(String name) { this.name = name; }
}

class BoolSetting extends Setting {
    public boolean value;
    public BoolSetting(String n, boolean v) { super(n); this.value = v; }
}

class NumSetting extends Setting {
    public double value, min, max, step;
    public boolean slider;
    public NumSetting(String n, double v, double mn, double mx, double s, boolean slider) {
        super(n); this.value = v; this.min = mn; this.max = mx; this.step = s; this.slider = slider;
    }
    public void set(double v) { this.value = Math.max(min, Math.min(max, Math.round(v / step) * step)); }
    public float f() { return (float) value; }
    public int i() { return (int) value; }
}

class ColorSetting extends Setting {
    public int rgba;
    public ModeSetting mode = null;
    public ColorSetting(String n, int rgba) { super(n); this.rgba = rgba; }
    public ColorSetting withMode(ModeSetting m) { this.mode = m; return this; }
    public int r() { return (rgba >> 16) & 0xFF; }
    public int g() { return (rgba >> 8) & 0xFF; }
    public int b() { return rgba & 0xFF; }
    public int a() { return (rgba >> 24) & 0xFF; }
    public int resolve(long time) {
        if (mode == null) return rgba;
        switch (mode.value) {
            case "Rainbow": {
                float hue = (time % 4000) / 4000f;
                int rgb = java.awt.Color.HSBtoRGB(hue, 0.8f, 1f);
                return (0xFF << 24) | (rgb & 0xFFFFFF);
            }
            case "Pulse": {
                float t = (float) ((Math.sin(time / 500.0) + 1) * 0.5);
                int r = (int) (((rgba >> 16) & 0xFF) * t);
                int g = (int) (((rgba >> 8) & 0xFF) * t);
                int b = (int) ((rgba & 0xFF) * t);
                return (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
            default: return rgba;
        }
    }
}

class ModeSetting extends Setting {
    public String value;
    public String[] options;
    public ModeSetting(String n, String def, String... opts) { super(n); this.value = def; this.options = opts; }
    public void next() {
        int idx = 0;
        for (int i = 0; i < options.length; i++) if (options[i].equals(value)) idx = i;
        value = options[(idx + 1) % options.length];
    }
}

abstract class Module {
    public final String name;
    public final Category category;
    private boolean enabled;
    public int key = -1;
    public boolean expanded = false;
    public final List<Setting> settings = new ArrayList<>();

    protected Module(String name, Category cat) { this.name = name; this.category = cat; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean s) {
        if (this.enabled == s) return;
        this.enabled = s;
        if (s) onEnable(); else onDisable();
    }
    public void toggle() { setEnabled(!enabled); }
    protected void onEnable() {}
    protected void onDisable() {}
    public void onTick() {}
    public void onRender2D(DrawContext ctx, float delta) {}
    public void onRender3D(MatrixStack ms, float delta) {}

    protected <S extends Setting> S add(S s) { settings.add(s); return s; }
}

class SimpleToggle extends Module {
    public SimpleToggle(String n, Category c) { super(n, c); }
}

class ModuleManager {
    public final List<Module> modules = new ArrayList<>();

    public void init() {
        reg(new KillAura());
        reg(new AutoClicker());
        reg(new VelocityMod());
        reg(new Reach());
        reg(new Criticals());
        reg(new Triggerbot());
        reg(new TargetStrafe());
        reg(new AimAssist());
        reg(new AutoJumpReset());
        reg(new SprintReset());
        reg(new HitSwap());
        reg(new ShieldBreaker());
        reg(new Backtrack());
        reg(new CrystalAura());
        reg(new AutoCrystal());
        reg(new AutoAnchor());
        reg(new AntiBot());
        reg(new AutoTrap());
        reg(new AutoWeb());
        reg(new AutoMace());
        reg(new AutoSpear());
        reg(new AutoElytraMace());
        reg(new AutoLunge());
        reg(new AutoXbow());
        reg(new CartAssist());
        reg(new CartRefill());
        reg(new PearlClutch());
        reg(new Piercing());
        reg(new FakeLag());
        reg(new AutoHitCrystal());
        reg(new AutoSpearDtap());

        reg(new Fly());
        reg(new Speed());
        reg(new NoFall());
        reg(new Scaffold());
        reg(new Step());
        reg(new Sprint());
        reg(new NoSlow());
        reg(new Parkour());
        reg(new ReverseStep());
        reg(new AntiHunger());
        reg(new AntiLevitation());
        reg(new AntiPush());
        reg(new NoJumpDelay());
        reg(new WindCharge());
        reg(new ElytraJump());
        reg(new SpinBot());
        reg(new MoveFix());
        reg(new InventoryWalk());
        reg(new AntiPowderSnow());
        reg(new SnapTap());

        reg(new ChestStealer());
        reg(new AutoArmour());
        reg(new FastPlace());
        reg(new NoBreakDelay());
        reg(new AutoTool());
        reg(new InventoryCleaner());
        reg(new MiddleClickFriend());
        reg(new NameProtect());
        reg(new KeyPearl());
        reg(new SilentTotem());
        reg(new HoverTotem());
        reg(new AntiGuiClose());
        reg(new AntiCob());
        reg(new Clutch());
        reg(new MLG());
        reg(new RightClicker());
        reg(new TargetList());
        reg(new XCarry());
        reg(new GhostHand());
        reg(new Disabler());
        reg(new Overtick());
        reg(new ClientSpoofer());
        reg(new CrystalOptimizer());

        reg(new PlayerESP());
        reg(new Nametags());
        reg(new Tracers());
        reg(new Fullbright());
        reg(new Chams());
        reg(new HUD());
        reg(new ArrayListMod());
        reg(new KeybindList());
        reg(new TargetHUD());
        reg(new TargetESP());
        reg(new StorageESP());
        reg(new ItemESP());
        reg(new HealthTags());
        reg(new ItemTags());
        reg(new BpsCounter());
        reg(new CustomCrosshair());
        reg(new NoHurtCam());
        reg(new NoBob());
        reg(new NoFOV());
        reg(new NoSwing());
        reg(new Freecam());
        reg(new Freelook());
        reg(new Zoom());
        reg(new ChinaHat());
        reg(new Wings());
        reg(new Hats());
        reg(new MotionBlur());
        reg(new TotemPop());
        reg(new TNTTimer());
        reg(new Trajectories());
        reg(new PotionHud());
        reg(new PlayerTrails());
        reg(new HitParticles());
        reg(new SkeletonESP());
        reg(new TrueSight());
        reg(new ShaderESP());

        reg(new Panic());
        reg(new SelfDestruct());
        reg(new StreamProof());
        reg(new DiscordRPC());
    }

    private void reg(Module m) { modules.add(m); }

    public Module get(String n) {
        return modules.stream().filter(m -> m.name.equalsIgnoreCase(n)).findFirst().orElse(null);
    }
    public boolean on(String n) { Module m = get(n); return m != null && m.isEnabled(); }
    public List<Module> byCategory(Category c) {
        return modules.stream().filter(m -> m.category == c).collect(Collectors.toList());
    }

    public void onTick() { for (Module m : modules) if (m.isEnabled()) m.onTick(); }
    public void onRender2D(DrawContext ctx, float d) { for (Module m : modules) if (m.isEnabled()) m.onRender2D(ctx, d); }
    public void onRender3D(MatrixStack ms, float d) { for (Module m : modules) if (m.isEnabled()) m.onRender3D(ms, d); }
}

class R {
    public static final RenderLayer LINES_NO_DEPTH = RenderLayer.of(
            "anon_lines",
            VertexFormats.POSITION_COLOR,
            VertexFormat.DrawMode.DEBUG_LINES,
            512, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.POSITION_COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .depthTest(RenderPhase.ALWAYS_DEPTH_TEST)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .build(false));

    public static void box(MatrixStack ms, Vec3d pos, float w, float h, int rgba) {
        MinecraftClient mc = MinecraftClient.getInstance();
        VertexConsumerProvider.Immediate vcp = mc.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = vcp.getBuffer(LINES_NO_DEPTH);
        Matrix4f mat = ms.peek().getPositionMatrix();
        float r = ((rgba >> 16) & 0xFF) / 255f;
        float g = ((rgba >> 8) & 0xFF) / 255f;
        float b = (rgba & 0xFF) / 255f;
        float a = ((rgba >> 24) & 0xFF) / 255f;

        double x1 = pos.x - w/2, x2 = pos.x + w/2;
        double y1 = pos.y, y2 = pos.y + h;
        double z1 = pos.z - w/2, z2 = pos.z + w/2;

        line(vc, mat, x1, y1, z1, x2, y1, z1, r, g, b, a);
        line(vc, mat, x2, y1, z1, x2, y1, z2, r, g, b, a);
        line(vc, mat, x2, y1, z2, x1, y1, z2, r, g, b, a);
        line(vc, mat, x1, y1, z2, x1, y1, z1, r, g, b, a);
        line(vc, mat, x1, y2, z1, x2, y2, z1, r, g, b, a);
        line(vc, mat, x2, y2, z1, x2, y2, z2, r, g, b, a);
        line(vc, mat, x2, y2, z2, x1, y2, z2, r, g, b, a);
        line(vc, mat, x1, y2, z2, x1, y2, z1, r, g, b, a);
        line(vc, mat, x1, y1, z1, x1, y2, z1, r, g, b, a);
        line(vc, mat, x2, y1, z1, x2, y2, z1, r, g, b, a);
        line(vc, mat, x2, y1, z2, x2, y2, z2, r, g, b, a);
        line(vc, mat, x1, y1, z2, x1, y2, z2, r, g, b, a);

        vcp.draw();
    }

    private static void line(VertexConsumer vc, Matrix4f m,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             float r, float g, float b, float a) {
        vc.vertex(m, (float) x1, (float) y1, (float) z1).color(r, g, b, a).normal(0, 1, 0);
        vc.vertex(m, (float) x2, (float) y2, (float) z2).color(r, g, b, a).normal(0, 1, 0);
    }

    public static void line3D(MatrixStack ms, Vec3d from, Vec3d to, int rgba) {
        MinecraftClient mc = MinecraftClient.getInstance();
        VertexConsumerProvider.Immediate vcp = mc.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = vcp.getBuffer(LINES_NO_DEPTH);
        Matrix4f m = ms.peek().getPositionMatrix();
        float r = ((rgba >> 16) & 0xFF) / 255f;
        float g = ((rgba >> 8) & 0xFF) / 255f;
        float b = (rgba & 0xFF) / 255f;
        float a = ((rgba >> 24) & 0xFF) / 255f;
        line(vc, m, from.x, from.y, from.z, to.x, to.y, to.z, r, g, b, a);
        vcp.draw();
    }

    public static void text3D(MatrixStack ms, Vec3d pos, String text, int color) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        ms.push();
        ms.translate(pos.x - mc.gameRenderer.getCamera().getPos().x,
                     pos.y - mc.gameRenderer.getCamera().getPos().y,
                     pos.z - mc.gameRenderer.getCamera().getPos().z);
        ms.multiply(mc.gameRenderer.getCamera().getRotation());
        ms.scale(-0.025f, -0.025f, 0.025f);
        tr.draw(text, -tr.getWidth(text) / 2f, 0, color, false,
                ms.peek().getPositionMatrix(),
                mc.getBufferBuilders().getEntityVertexConsumers(),
                TextRenderer.TextLayerType.SEE_THROUGH, 0, 0xF000F0);
        ms.pop();
    }
}

class KillAura extends Module {
    final NumSetting range = add(new NumSetting("Range", 3.2, 1, 6, 0.1, true));
    final NumSetting cps = add(new NumSetting("CPS", 10, 1, 20, 1, true));
    final NumSetting rotSpeed = add(new NumSetting("RotSpeed", 120, 10, 500, 10, true));
    final BoolSetting players = add(new BoolSetting("Players", true));
    final BoolSetting mobs = add(new BoolSetting("Mobs", true));
    final BoolSetting rotate = add(new BoolSetting("Rotate", true));
    final BoolSetting throughWalls = add(new BoolSetting("ThroughWalls", false));
    final ModeSetting sortMode = add(new ModeSetting("Sort", "Distance", "Distance", "Health", "Angle"));
    private long nextHit;
    private final Random rng = new Random();

    public KillAura() { super("KillAura", Category.COMBAT); key = GLFW.GLFW_KEY_R; }

    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;
        long now = System.currentTimeMillis();
        if (now < nextHit) return;

        Entity target = pick(mc);
        if (target == null) return;

        if (rotate.value) {
            Vec3d dir = target.getEyePos().subtract(mc.player.getEyePos());
            float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
            float pitch = (float) Math.toDegrees(Math.atan2(-dir.y, Math.hypot(dir.x, dir.z)));
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
        }

        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);

        int cpsVal = cps.i();
        nextHit = now + (1000L / Math.max(1, cpsVal + rng.nextInt(3) - 1));
    }

    private Entity pick(MinecraftClient mc) {
        Entity best = null;
        double bestVal = Double.MAX_VALUE;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity)) continue;
            if (e == mc.player) continue;
            if (e instanceof PlayerEntity && !players.value) continue;
            if (!(e instanceof PlayerEntity) && !mobs.value) continue;
            if (((LivingEntity) e).getHealth() <= 0) continue;
            if (AnonClient.FRIENDS.contains(e.getName().getString())) continue;
            double d = mc.player.distanceTo(e);
            if (d > range.value) continue;
            if (!throughWalls.value && !mc.player.canSee(e)) continue;

            double val;
            switch (sortMode.value) {
                case "Health": val = ((LivingEntity) e).getHealth(); break;
                case "Angle": {
                    Vec3d dir = e.getPos().subtract(mc.player.getPos()).normalize();
                    Vec3d look = mc.player.getRotationVec(1f);
                    val = 1 - dir.dotProduct(look);
                    break;
                }
                default: val = d;
            }
            if (val < bestVal) { bestVal = val; best = e; }
        }
        return best;
    }
}

class AutoClicker extends Module {
    final NumSetting minCps = add(new NumSetting("MinCPS", 8, 1, 20, 1, true));
    final NumSetting maxCps = add(new NumSetting("MaxCPS", 12, 1, 20, 1, true));
    final BoolSetting onlyLeft = add(new BoolSetting("OnlyLeftClick", true));
    final BoolSetting weaponOnly = add(new BoolSetting("WeaponOnly", false));
    private long nextClick;
    private final Random rng = new Random();

    public AutoClicker() { super("AutoClicker", Category.COMBAT); }

    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.currentScreen != null) return;
        if (onlyLeft.value && !mc.options.attackKey.isPressed()) return;
        if (weaponOnly.value && !(mc.player.getMainHandStack().getItem() instanceof net.minecraft.item.SwordItem
                || mc.player.getMainHandStack().getItem() instanceof net.minecraft.item.AxeItem)) return;
        if (System.currentTimeMillis() < nextClick) return;

        if (mc.crosshairTarget instanceof EntityHitResult ehr) {
            mc.interactionManager.attackEntity(mc.player, ehr.getEntity());
        }
        mc.player.swingHand(Hand.MAIN_HAND);
        int mn = minCps.i(), mx = Math.max(mn, maxCps.i());
        int cps = mn + rng.nextInt(mx - mn + 1);
        nextClick = System.currentTimeMillis() + (1000L / cps);
    }
}

class VelocityMod extends Module {
    final NumSetting horizontal = add(new NumSetting("Horizontal", 0, 0, 100, 1, true));
    final NumSetting vertical = add(new NumSetting("Vertical", 0, 0, 100, 1, true));
    public VelocityMod() { super("Velocity", Category.COMBAT); }
}

class Reach extends Module {
    final NumSetting distance = add(new NumSetting("Distance", 3.1, 3, 6, 0.1, true));
    public Reach() { super("Reach", Category.COMBAT); }
}

class Criticals extends Module {
    final ModeSetting mode = add(new ModeSetting("Mode", "Packet", "Packet", "Jump", "MiniJump"));
    public Criticals() { super("Criticals", Category.COMBAT); }
    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !mc.options.attackKey.isPressed()) return;
        if (!mc.player.isOnGround()) return;
        if (mode.value.equals("Packet")) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                    mc.player.getX(), mc.player.getY() + 0.0625, mc.player.getZ(), false, mc.player.horizontalCollision));
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(), false, mc.player.horizontalCollision));
        }
    }
}

class Triggerbot extends Module {
    final NumSetting delay = add(new NumSetting("Delay", 50, 0, 500, 10, true));
    private long last;
    public Triggerbot() { super("Triggerbot", Category.COMBAT); }
    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.currentScreen != null) return;
        if (System.currentTimeMillis() - last < delay.value) return;
        if (mc.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity) {
            mc.interactionManager.attackEntity(mc.player, ehr.getEntity());
            mc.player.swingHand(Hand.MAIN_HAND);
            last = System.currentTimeMillis();
        }
    }
}

class TargetStrafe extends Module {
    final NumSetting radius = add(new NumSetting("Radius", 2.5, 1, 5, 0.1, true));
    final NumSetting speed = add(new NumSetting("Speed", 0.3, 0.1, 1, 0.05, true));
    public TargetStrafe() { super("TargetStrafe", Category.COMBAT); }
    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        Entity t = null; double best = 6;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof PlayerEntity) || e == mc.player) continue;
            double d = mc.player.distanceTo(e);
            if (d < best) { best = d; t = e; }
        }
        if (t == null) return;
        Vec3d dir = t.getPos().subtract(mc.player.getPos());
        Vec3d strafe = new Vec3d(-dir.z, 0, dir.x).normalize();
        mc.player.addVelocity(strafe.x * speed.value, 0, strafe.z * speed.value);
    }
}

class AimAssist extends Module {
    final NumSetting range = add(new NumSetting("Range", 4.0, 1, 8, 0.1, true));
    final NumSetting strength = add(new NumSetting("Strength", 40, 1, 100, 1, true));
    final NumSetting fov = add(new NumSetting("FOV", 90, 10, 180, 5, true));
    public AimAssist() { super("AimAssist", Category.COMBAT); key = GLFW.GLFW_KEY_R; }
    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        PlayerEntity best = null; double bestAngle = fov.value;
        Vec3d look = mc.player.getRotationVec(1f);
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (mc.player.distanceTo(p) > range.value) continue;
            Vec3d dir = p.getEyePos().subtract(mc.player.getEyePos()).normalize();
            double ang = Math.toDegrees(Math.acos(MathHelper.clamp(dir.dotProduct(look), -1, 1)));
            if (ang < bestAngle) { bestAngle = ang; best = p; }
        }
        if (best == null) return;
        Vec3d dir = best.getEyePos().subtract(mc.player.getEyePos());
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-dir.y, Math.hypot(dir.x, dir.z)));
        float factor = strength.f() / 100f;
        mc.player.setYaw(mc.player.getYaw() + MathHelper.wrapDegrees(yaw - mc.player.getYaw()) * factor);
        mc.player.setPitch(mc.player.getPitch() + (pitch - mc.player.getPitch()) * factor);
    }
}

class AutoJumpReset extends SimpleToggle { public AutoJumpReset() { super("AutoJumpReset", Category.COMBAT); } }
class SprintReset extends SimpleToggle { final NumSetting delay = add(new NumSetting("Delay", 1, 0, 10, 1, true)); public SprintReset() { super("SprintReset", Category.COMBAT); } }
class HitSwap extends SimpleToggle { public HitSwap() { super("HitSwap", Category.COMBAT); } }
class ShieldBreaker extends SimpleToggle { public ShieldBreaker() { super("ShieldBreaker", Category.COMBAT); } }
class Backtrack extends SimpleToggle { final NumSetting delay = add(new NumSetting("Delay", 100, 50, 500, 10, true)); public Backtrack() { super("Backtrack", Category.COMBAT); } }
class CrystalAura extends SimpleToggle { final NumSetting range = add(new NumSetting("Range", 4.5, 1, 6, 0.1, true)); public CrystalAura() { super("CrystalAura", Category.COMBAT); } }
class AutoCrystal extends SimpleToggle { public AutoCrystal() { super("AutoCrystal", Category.COMBAT); } }
class AutoAnchor extends SimpleToggle { public AutoAnchor() { super("AutoAnchor", Category.COMBAT); } }
class AntiBot extends SimpleToggle { public AntiBot() { super("AntiBot", Category.COMBAT); } }
class AutoTrap extends SimpleToggle { public AutoTrap() { super("AutoTrap", Category.COMBAT); } }
class AutoWeb extends SimpleToggle { public AutoWeb() { super("AutoWeb", Category.COMBAT); } }
class AutoMace extends SimpleToggle { public AutoMace() { super("AutoMace", Category.COMBAT); } }
class AutoSpear extends SimpleToggle { public AutoSpear() { super("AutoSpear", Category.COMBAT); } }
class AutoElytraMace extends SimpleToggle { public AutoElytraMace() { super("AutoElytraMace", Category.COMBAT); } }
class AutoLunge extends SimpleToggle { public AutoLunge() { super("AutoLunge", Category.COMBAT); } }
class AutoXbow extends SimpleToggle { public AutoXbow() { super("AutoXbow", Category.COMBAT); } }
class CartAssist extends SimpleToggle { public CartAssist() { super("CartAssist", Category.COMBAT); } }
class CartRefill extends SimpleToggle { public CartRefill() { super("CartRefill", Category.COMBAT); } }
class PearlClutch extends SimpleToggle { public PearlClutch() { super("PearlClutch", Category.COMBAT); } }
class Piercing extends SimpleToggle { public Piercing() { super("Piercing", Category.COMBAT); } }
class FakeLag extends SimpleToggle { public FakeLag() { super("FakeLag", Category.COMBAT); } }
class AutoHitCrystal extends SimpleToggle { public AutoHitCrystal() { super("AutoHitCrystal", Category.COMBAT); } }
class AutoSpearDtap extends SimpleToggle { public AutoSpearDtap() { super("AutoSpearDtap", Category.COMBAT); } }

class Fly extends Module {
    final NumSetting speed = add(new NumSetting("Speed", 1.0, 0.1, 5, 0.1, true));
    public Fly() { super("Fly", Category.MOVEMENT); }
    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        Vec3d dir = Vec3d.ZERO;
        Vec3d look = mc.player.getRotationVec(1f);
        if (mc.options.forwardKey.isPressed()) dir = dir.add(look.x, 0, look.z);
        if (mc.options.backKey.isPressed()) dir = dir.subtract(look.x, 0, look.z);
        if (mc.options.leftKey.isPressed()) dir = dir.add(look.z, 0, -look.x);
        if (mc.options.rightKey.isPressed()) dir = dir.subtract(look.z, 0, -look.x);
        if (mc.options.jumpKey.isPressed()) dir = dir.add(0, 1, 0);
        if (mc.options.sneakKey.isPressed()) dir = dir.add(0, -1, 0);
        if (dir.lengthSquared() > 0) dir = dir.normalize().multiply(speed.value);
        mc.player.setVelocity(dir);
        mc.player.setOnGround(false);
    }
}

class Speed extends Module {
    final NumSetting multiplier = add(new NumSetting("Multiplier", 1.4, 1, 3, 0.05, true));
    public Speed() { super("Speed", Category.MOVEMENT); }
    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !mc.player.isOnGround()) return;
        Vec3d v = mc.player.getVelocity();
        mc.player.setVelocity(v.x * multiplier.value, v.y, v.z * multiplier.value);
    }
}

class NoFall extends Module {
    public NoFall() { super("NoFall", Category.MOVEMENT); }
    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        if (mc.player.fallDistance > 2.5f) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true, mc.player.horizontalCollision));
            mc.player.fallDistance = 0f;
        }
    }
}

class Scaffold extends Module {
    final NumSetting delay = add(new NumSetting("Delay", 2, 1, 10, 1, true));
    private int tick = 0;
    public Scaffold() { super("Scaffold", Category.MOVEMENT); }
    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (++tick % delay.i() != 0) return;
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof BlockItem) { slot = i; break; }
        }
        if (slot == -1) return;
        mc.player.getInventory().selectedSlot = slot;
        BlockPos below = mc.player.getBlockPos().down();
        if (mc.world.getBlockState(below).isAir()) {
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(below), Direction.UP, below, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }
}

class Step extends SimpleToggle { public Step() { super("Step", Category.MOVEMENT); } }
class Sprint extends SimpleToggle { public Sprint() { super("Sprint", Category.MOVEMENT); } }
class NoSlow extends SimpleToggle { public NoSlow() { super("NoSlow", Category.MOVEMENT); } }
class Parkour extends SimpleToggle { public Parkour() { super("Parkour", Category.MOVEMENT); } }
class ReverseStep extends SimpleToggle { public ReverseStep() { super("ReverseStep", Category.MOVEMENT); } }
class AntiHunger extends SimpleToggle { public AntiHunger() { super("AntiHunger", Category.MOVEMENT); } }
class AntiLevitation extends SimpleToggle { public AntiLevitation() { super("AntiLevitation", Category.MOVEMENT); } }
class AntiPush extends SimpleToggle { public AntiPush() { super("AntiPush", Category.MOVEMENT); } }
class NoJumpDelay extends SimpleToggle { public NoJumpDelay() { super("NoJumpDelay", Category.MOVEMENT); } }
class WindCharge extends SimpleToggle { public WindCharge() { super("WindCharge", Category.MOVEMENT); } }
class ElytraJump extends SimpleToggle { public ElytraJump() { super("ElytraJump", Category.MOVEMENT); } }
class SpinBot extends SimpleToggle { public SpinBot() { super("SpinBot", Category.MOVEMENT); } }
class MoveFix extends SimpleToggle { public MoveFix() { super("MoveFix", Category.MOVEMENT); } }
class InventoryWalk extends SimpleToggle { public InventoryWalk() { super("InventoryWalk", Category.MOVEMENT); } }
class AntiPowderSnow extends SimpleToggle { public AntiPowderSnow() { super("AntiPowderSnow", Category.MOVEMENT); } }
class SnapTap extends SimpleToggle { public SnapTap() { super("SnapTap", Category.MOVEMENT); } }

class ChestStealer extends Module {
    final NumSetting delay = add(new NumSetting("Delay", 3, 1, 10, 1, true));
    private int tick = 0;
    public ChestStealer() { super("ChestStealer", Category.UTILITY); }
    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler g)) return;
        if (++tick % delay.i() != 0) return;
        for (int i = 0; i < g.getInventory().size(); i++) {
            var s = g.getSlot(i).getStack();
            if (!s.isEmpty()) {
                mc.interactionManager.clickSlot(g.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                return;
            }
        }
    }
}

class AutoArmour extends Module {
    private int tick = 0;
    public AutoArmour() { super("AutoArmour", Category.UTILITY); }
    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        if (++tick % 10 != 0) return;
        for (int i = 0; i < 36; i++) {
            var stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() instanceof ArmorItem armor) {
                int target = switch (armor.getSlotType()) {
                    case HEAD -> 39; case CHEST -> 38; case LEGS -> 37; case FEET -> 36;
                    default -> -1;
                };
                if (target == -1) continue;
                if (mc.player.getInventory().getStack(target).isEmpty()) {
                    mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                    return;
                }
            }
        }
    }
}

class AutoTool extends Module {
    public AutoTool() { super("AutoTool", Category.UTILITY); }
    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (!(mc.crosshairTarget instanceof BlockHitResult bhr)) return;
        var state = mc.world.getBlockState(bhr.getBlockPos());
        if (state.isAir()) return;
        int bestSlot = -1; float bestSpeed = 1f;
        for (int i = 0; i < 9; i++) {
            var stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) { bestSpeed = speed; bestSlot = i; }
        }
        if (bestSlot != -1) mc.player.getInventory().selectedSlot = bestSlot;
    }
}

class MiddleClickFriend extends SimpleToggle { public MiddleClickFriend() { super("MiddleClickFriend", Category.UTILITY); } }
class InventoryCleaner extends SimpleToggle { public InventoryCleaner() { super("InventoryCleaner", Category.UTILITY); } }
class FastPlace extends SimpleToggle { public FastPlace() { super("FastPlace", Category.UTILITY); } }
class NoBreakDelay extends SimpleToggle { public NoBreakDelay() { super("NoBreakDelay", Category.UTILITY); } }
class NameProtect extends SimpleToggle { public NameProtect() { super("NameProtect", Category.UTILITY); } }
class KeyPearl extends SimpleToggle { public KeyPearl() { super("KeyPearl", Category.UTILITY); } }
class SilentTotem extends SimpleToggle { public SilentTotem() { super("SilentTotem", Category.UTILITY); } }
class HoverTotem extends SimpleToggle { public HoverTotem() { super("HoverTotem", Category.UTILITY); } }
class AntiGuiClose extends SimpleToggle { public AntiGuiClose() { super("AntiGuiClose", Category.UTILITY); } }
class AntiCob extends SimpleToggle { public AntiCob() { super("AntiCob", Category.UTILITY); } }
class Clutch extends SimpleToggle { public Clutch() { super("Clutch", Category.UTILITY); } }
class MLG extends SimpleToggle { public MLG() { super("MLG", Category.UTILITY); } }
class RightClicker extends SimpleToggle { public RightClicker() { super("RightClicker", Category.UTILITY); } }
class TargetList extends SimpleToggle { public TargetList() { super("Targets", Category.UTILITY); } }
class XCarry extends SimpleToggle { public XCarry() { super("XCarry", Category.UTILITY); } }
class GhostHand extends SimpleToggle { public GhostHand() { super("GhostHand", Category.UTILITY); } }
class Disabler extends SimpleToggle { public Disabler() { super("Disabler", Category.UTILITY); } }
class Overtick extends SimpleToggle { public Overtick() { super("Overtick", Category.UTILITY); } }
class ClientSpoofer extends SimpleToggle { public ClientSpoofer() { super("ClientSpoofer", Category.UTILITY); } }
class CrystalOptimizer extends SimpleToggle { public CrystalOptimizer() { super("CrystalOptimizer", Category.UTILITY); } }

class PlayerESP extends Module {
    final ColorSetting color = add(new ColorSetting("Color", 0xFF3C78C8))
            .withMode((ModeSetting) add(new ModeSetting("ColorMode", "Static", "Static", "Rainbow", "Pulse")));
    final NumSetting width = add(new NumSetting("Width", 0.8, 0.2, 2, 0.1, true));
    final NumSetting height = add(new NumSetting("Height", 1.8, 0.5, 3, 0.1, true));
    final BoolSetting friendFilter = add(new BoolSetting("SkipFriends", true));
    public PlayerESP() { super("PlayerESP", Category.RENDER); }
    @Override public void onRender3D(MatrixStack ms, float d) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        int c = color.resolve(System.currentTimeMillis());
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (friendFilter.value && AnonClient.FRIENDS.contains(p.getName().getString())) continue;
            Vec3d pos = p.getLerpedPos(d);
            R.box(ms, pos, width.f(), height.f(), c);
        }
    }
}

class Nametags extends Module {
    final ColorSetting color = add(new ColorSetting("Color", 0xFFFFFFFF))
            .withMode((ModeSetting) add(new ModeSetting("ColorMode", "Static", "Static", "Rainbow", "Pulse")));
    final BoolSetting showDist = add(new BoolSetting("ShowDistance", true));
    final BoolSetting showHealth = add(new BoolSetting("ShowHealth", true));
    public Nametags() { super("Nametags", Category.RENDER); }
    @Override public void onRender3D(MatrixStack ms, float d) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        int c = color.resolve(System.currentTimeMillis());
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            Vec3d pos = p.getLerpedPos(d).add(0, 2.2, 0);
            StringBuilder sb = new StringBuilder(p.getName().getString());
            if (showHealth.value) sb.append(" §c").append((int) p.getHealth()).append("§f");
            if (showDist.value) sb.append(" §7").append(String.format("%.1f", mc.player.distanceTo(p)));
            R.text3D(ms, pos, sb.toString(), c);
        }
    }
}

class Tracers extends Module {
    final ColorSetting color = add(new ColorSetting("Color", 0xFFFF3C3C))
            .withMode((ModeSetting) add(new ModeSetting("ColorMode", "Static", "Static", "Rainbow", "Pulse")));
    public Tracers() { super("Tracers", Category.RENDER); }
    @Override public void onRender3D(MatrixStack ms, float d) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        int c = color.resolve(System.currentTimeMillis());
        Vec3d cam = mc.gameRenderer.getCamera().getPos();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            Vec3d target = p.getLerpedPos(d).add(0, 1.2, 0);
            R.line3D(ms, cam, target, c);
        }
    }
}

class Fullbright extends Module {
    public Fullbright() { super("Fullbright", Category.RENDER); }
    public static boolean active() { return AnonClient.MODULE_MANAGER != null && AnonClient.MODULE_MANAGER.on("Fullbright"); }
}

class Chams extends Module {
    final ColorSetting color = add(new ColorSetting("Color", 0x80FF3C3C));
    public Chams() { super("Chams", Category.RENDER); }
    @Override public void onRender3D(MatrixStack ms, float d) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            R.box(ms, p.getLerpedPos(d), 0.6f, 1.8f, color.rgba);
        }
    }
}

class HUD extends Module {
    final BoolSetting watermark = add(new BoolSetting("Watermark", true));
    final BoolSetting coords = add(new BoolSetting("Coords", true));
    final BoolSetting fps = add(new BoolSetting("FPS", true));
    final BoolSetting ping = add(new BoolSetting("Ping", true));
    final ColorSetting textColor = add(new ColorSetting("TextColor", 0xFFFFFFFF));
    public HUD() { super("HUD", Category.RENDER); }
    @Override public void onRender2D(DrawContext ctx, float d) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        int y = 4;
        if (watermark.value) { ctx.drawText(tr, "§bANON §7v1.0", 4, y, textColor.rgba, true); y += 12; }
        if (coords.value) { ctx.drawText(tr, String.format("§7XYZ §f%d %d %d", (int) mc.player.getX(), (int) mc.player.getY(), (int) mc.player.getZ()), 4, y, textColor.rgba, true); y += 10; }
        if (fps.value) { ctx.drawText(tr, "§7FPS §f" + mc.getCurrentFps(), 4, y, textColor.rgba, true); y += 10; }
        if (ping.value) {
            var entry = mc.getNetworkHandler() != null ? mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid()) : null;
            int p = entry != null ? entry.getLatency() : 0;
            ctx.drawText(tr, "§7Ping §f" + p + "ms", 4, y, textColor.rgba, true);
        }
    }
}

class ArrayListMod extends Module {
    final ColorSetting color = add(new ColorSetting("Color", 0xFF3CC8FF))
            .withMode((ModeSetting) add(new ModeSetting("ColorMode", "Rainbow", "Static", "Rainbow", "Pulse")));
    final BoolSetting right = add(new BoolSetting("RightSide", true));
    public ArrayListMod() { super("ArrayList", Category.RENDER); }
    @Override public void onRender2D(DrawContext ctx, float d) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        List<Module> enabled = AnonClient.MODULE_MANAGER.modules.stream()
                .filter(Module::isEnabled).sorted((a, b) -> tr.getWidth(b.name) - tr.getWidth(a.name)).toList();
        long now = System.currentTimeMillis();
        int y = 4;
        for (Module m : enabled) {
            int c = color.resolve(now + y * 40L);
            int w = tr.getWidth(m.name);
            int x = right.value ? mc.getWindow().getScaledWidth() - w - 6 : 4;
            ctx.fill(x - 2, y - 1, x + w + 2, y + 10, 0x80000000);
            ctx.drawText(tr, m.name, x, y, c, true);
            y += 12;
        }
    }
}

class KeybindList extends SimpleToggle { public KeybindList() { super("KeybindList", Category.RENDER); } }

class TargetHUD extends Module {
    final ColorSetting accent = add(new ColorSetting("Accent", 0xFFFF3C78))
            .withMode((ModeSetting) add(new ModeSetting("ColorMode", "Static", "Static", "Rainbow", "Pulse")));
    public TargetHUD() { super("TargetHUD", Category.RENDER); }
    @Override public void onRender2D(DrawContext ctx, float d) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        PlayerEntity best = null; double bestD = 6;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            double dd = mc.player.distanceTo(p);
            if (dd < bestD) { bestD = dd; best = p; }
        }
        if (best == null) return;
        int c = accent.resolve(System.currentTimeMillis());
        int x = mc.getWindow().getScaledWidth() / 2 - 60;
        int y = mc.getWindow().getScaledHeight() / 2 + 40;
        ctx.fill(x, y, x + 120, y + 30, 0xC0000000);
        ctx.fill(x, y, x + 120, y + 1, c);
        ctx.drawText(mc.textRenderer, best.getName().getString(), x + 4, y + 4, 0xFFFFFFFF, true);
        float hp = best.getHealth() / best.getMaxHealth();
        ctx.fill(x + 4, y + 20, x + 116, y + 26, 0xFF333333);
        ctx.fill(x + 4, y + 20, x + 4 + (int) (112 * hp), y + 26, 0xFFFF3C3C);
    }
}

class TargetESP extends Module {
    final ColorSetting color = add(new ColorSetting("Color", 0xFFFF3C3C));
    public TargetESP() { super("TargetESP", Category.RENDER); }
    @Override public void onRender3D(MatrixStack ms, float d) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        Entity best = null; double bestD = 6;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof PlayerEntity) || e == mc.player) continue;
            double dd = mc.player.distanceTo(e);
            if (dd < bestD) { bestD = dd; best = e; }
        }
        if (best != null) R.box(ms, best.getLerpedPos(d), 1.0f, 2.1f, color.rgba);
    }
}

class StorageESP extends Module {
    final ColorSetting color = add(new ColorSetting("Color", 0xFFFFA500));
    final NumSetting range = add(new NumSetting("Range", 32, 8, 128, 8, true));
    public StorageESP() { super("StorageESP", Category.RENDER); }
    @Override public void onRender3D(MatrixStack ms, float d) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        BlockPos origin = mc.player.getBlockPos();
        int r = (int) range.value;
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -r, -r), origin.add(r, r, r))) {
            var be = mc.world.getBlockEntity(pos);
            if (be instanceof net.minecraft.block.entity.ChestBlockEntity
                    || be instanceof net.minecraft.block.entity.BarrelBlockEntity
                    || be instanceof net.minecraft.block.entity.ShulkerBoxBlockEntity) {
                R.box(ms, Vec3d.ofCenter(pos), 1.0f, 1.0f, color.rgba);
            }
        }
    }
}

class ItemESP extends Module {
    final ColorSetting color = add(new ColorSetting("Color", 0xFFFFFF00));
    public ItemESP() { super("ItemESP", Category.RENDER); }
    @Override public void onRender3D(MatrixStack ms, float d) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof net.minecraft.entity.ItemEntity) {
                R.box(ms, e.getLerpedPos(d), 0.5f, 0.5f, color.rgba);
            }
        }
    }
}

class HealthTags extends SimpleToggle { public HealthTags() { super("HealthTags", Category.RENDER); } }
class ItemTags extends SimpleToggle { public ItemTags() { super("ItemTags", Category.RENDER); } }
class BpsCounter extends SimpleToggle { public BpsCounter() { super("BpsCounter", Category.RENDER); } }

class CustomCrosshair extends Module {
    final ColorSetting color = add(new ColorSetting("Color", 0xFFFFFFFF));
    final NumSetting size = add(new NumSetting("Size", 4, 1, 20, 1, true));
    public CustomCrosshair() { super("CustomCrosshair", Category.RENDER); }
    @Override public void onRender2D(DrawContext ctx, float d) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int cx = mc.getWindow().getScaledWidth() / 2;
        int cy = mc.getWindow().getScaledHeight() / 2;
        int s = size.i();
        ctx.fill(cx - s, cy, cx + s, cy + 1, color.rgba);
        ctx.fill(cx, cy - s, cx + 1, cy + s, color.rgba);
    }
}

class NoHurtCam extends SimpleToggle { public NoHurtCam() { super("NoHurtCam", Category.RENDER); } }
class NoBob extends SimpleToggle { public NoBob() { super("NoBob", Category.RENDER); } }
class NoFOV extends SimpleToggle { public NoFOV() { super("NoFOV", Category.RENDER); } }
class NoSwing extends SimpleToggle { public NoSwing() { super("NoSwing", Category.RENDER); } }
class Freecam extends SimpleToggle { public Freecam() { super("Freecam", Category.RENDER); } }
class Freelook extends SimpleToggle { public Freelook() { super("Freelook", Category.RENDER); } }
class Zoom extends SimpleToggle { public Zoom() { super("Zoom", Category.RENDER); } }
class ChinaHat extends SimpleToggle { public ChinaHat() { super("ChinaHat", Category.RENDER); } }
class Wings extends SimpleToggle { public Wings() { super("Wings", Category.RENDER); } }
class Hats extends SimpleToggle { public Hats() { super("Hats", Category.RENDER); } }
class MotionBlur extends SimpleToggle { public MotionBlur() { super("MotionBlur", Category.RENDER); } }
class TotemPop extends SimpleToggle { public TotemPop() { super("TotemPop", Category.RENDER); } }
class TNTTimer extends SimpleToggle { public TNTTimer() { super("TNTTimer", Category.RENDER); } }
class Trajectories extends SimpleToggle { public Trajectories() { super("Trajectories", Category.RENDER); } }
class PotionHud extends SimpleToggle { public PotionHud() { super("PotionHud", Category.RENDER); } }
class PlayerTrails extends SimpleToggle { public PlayerTrails() { super("PlayerTrails", Category.RENDER); } }
class HitParticles extends SimpleToggle { public HitParticles() { super("HitParticles", Category.RENDER); } }
class SkeletonESP extends SimpleToggle { public SkeletonESP() { super("SkeletonESP", Category.RENDER); } }
class TrueSight extends SimpleToggle { public TrueSight() { super("TrueSight", Category.RENDER); } }
class ShaderESP extends SimpleToggle { public ShaderESP() { super("ShaderESP", Category.RENDER); } }

class Panic extends Module {
    public Panic() { super("Panic", Category.CLIENT); key = GLFW.GLFW_KEY_P; }
    @Override protected void onEnable() {
        for (Module m : AnonClient.MODULE_MANAGER.modules) {
            if (m != this && m.isEnabled()) m.setEnabled(false);
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof ClickGUI) mc.setScreen(null);
        setEnabled(false);
    }
}

class SelfDestruct extends Module {
    public SelfDestruct() { super("SelfDestruct", Category.CLIENT); }
    @Override protected void onEnable() {
        for (Module m : AnonClient.MODULE_MANAGER.modules) if (m != this) m.setEnabled(false);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != null) mc.setScreen(null);
        setEnabled(false);
    }
}

class StreamProof extends SimpleToggle { public StreamProof() { super("StreamProof", Category.CLIENT); } }
class DiscordRPC extends SimpleToggle { public DiscordRPC() { super("DiscordRPC", Category.CLIENT); } }

class ClickGUI extends Screen {
    private Category currentCat = Category.COMBAT;
    private int panelX = 40, panelY = 40;
    private final int panelW = 340, panelH = 360;
    private NumSetting dragging = null;
    private Setting colorPickTarget = null;

    public ClickGUI() { super(Text.literal("ANON")); }

    @Override public void render(DrawContext ctx, int mx, int my, float d) {
        renderBackground(ctx, mx, my, d);
        ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF00A0A0E);
        ctx.fill(panelX, panelY, panelX + panelW, panelY + 20, 0xFF1A1A22);
        ctx.drawText(textRenderer, "§bANON §7v1.0 §8| §7press §f0§7 to toggle", panelX + 6, panelY + 6, 0xFFFFFFFF, false);

        int tabX = panelX + 4;
        for (Category c : Category.values()) {
            int w = textRenderer.getWidth(c.name()) + 10;
            boolean active = c == currentCat;
            ctx.fill(tabX, panelY + 22, tabX + w, panelY + 36, active ? 0xFF3C78C8 : 0xFF181820);
            ctx.drawText(textRenderer, c.name(), tabX + 5, panelY + 26, 0xFFFFFFFF, false);
            if (mx >= tabX && mx <= tabX + w && my >= panelY + 22 && my <= panelY + 36 && isClicking(mx, my)) {
                currentCat = c;
            }
            tabX += w + 2;
        }

        int listX = panelX + 6, listY = panelY + 40;
        int listW = panelW - 12;
        int yy = listY - scroll;
        for (Module m : AnonClient.MODULE_MANAGER.byCategory(currentCat)) {
            int rowH = 18;
            int c = m.isEnabled() ? 0xFF3C78C8 : 0xFF1E1E26;
            ctx.fill(listX, yy, listX + listW, yy + rowH, c);
            ctx.drawText(textRenderer, m.name, listX + 4, yy + 5, 0xFFFFFFFF, false);
            if (!m.settings.isEmpty()) {
                ctx.drawText(textRenderer, m.expanded ? "▾" : "▸", listX + listW - 12, yy + 5, 0xFFAAAAAA, false);
            }
            yy += rowH + 2;
            if (m.expanded) {
                for (Setting s : m.settings) {
                    yy = drawSetting(ctx, s, listX + 8, yy, listW - 16, mx, my);
                }
                yy += 2;
            }
        }
        if (colorPickTarget != null) drawColorPicker(ctx, mx, my);
        super.render(ctx, mx, my, d);
    }

    private int scroll = 0;

    private int drawSetting(DrawContext ctx, Setting s, int x, int y, int w, int mx, int my) {
        if (s instanceof BoolSetting b) {
            int c = b.value ? 0xFF3C78C8 : 0xFF2A2A32;
            ctx.fill(x, y, x + w, y + 14, c);
            ctx.drawText(textRenderer, b.name + (b.value ? " ✓" : ""), x + 4, y + 3, 0xFFFFFFFF, false);
            if (isClick(mx, my, x, y, w, 14)) b.value = !b.value;
            return y + 16;
        }
        if (s instanceof NumSetting n) {
            ctx.fill(x, y, x + w, y + 14, 0xFF1A1A20);
            int fillW = (int) ((n.value - n.min) / (n.max - n.min) * w);
            ctx.fill(x, y, x + fillW, y + 14, 0xFF3C78C8);
            ctx.drawText(textRenderer, n.name + ": " + String.format("%.1f", n.value), x + 4, y + 3, 0xFFFFFFFF, false);
            if (mouseDown && isClick(mx, my, x, y, w, 14)) dragging = n;
            return y + 16;
        }
        if (s instanceof ModeSetting m) {
            ctx.fill(x, y, x + w, y + 14, 0xFF2A2A32);
            ctx.drawText(textRenderer, m.name + ": " + m.value, x + 4, y + 3, 0xFFFFFFFF, false);
            if (isClick(mx, my, x, y, w, 14)) m.next();
            return y + 16;
        }
        if (s instanceof ColorSetting c) {
            ctx.fill(x, y, x + w, y + 14, 0xFF1A1A20);
            ctx.fill(x + w - 20, y + 2, x + w - 2, y + 12, c.rgba);
            ctx.drawText(textRenderer, c.name, x + 4, y + 3, 0xFFFFFFFF, false);
            if (isClick(mx, my, x, y, w, 14)) colorPickTarget = c;
            return y + 16;
        }
        return y + 16;
    }

    private void drawColorPicker(DrawContext ctx, int mx, int my) {
        ColorSetting c = (ColorSetting) colorPickTarget;
        int x = panelX + panelW + 10, y = panelY;
        ctx.fill(x, y, x + 140, y + 140, 0xF0151518);
        ctx.drawText(textRenderer, "Color Picker", x + 4, y + 4, 0xFFFFFFFF, false);
        ctx.fill(x + 4, y + 18, x + 136, y + 44, c.rgba);

        drawPickSlider(ctx, "R", x + 4, y + 50, 132, c.r(), mx, my, v -> c.rgba = (c.rgba & 0xFF00FFFF) | (v << 16));
        drawPickSlider(ctx, "G", x + 4, y + 70, 132, c.g(), mx, my, v -> c.rgba = (c.rgba & 0xFFFF00FF) | (v << 8));
        drawPickSlider(ctx, "B", x + 4, y + 90, 132, c.b(), mx, my, v -> c.rgba = (c.rgba & 0xFFFFFF00) | v);
        drawPickSlider(ctx, "A", x + 4, y + 110, 132, c.a(), mx, my, v -> c.rgba = (c.rgba & 0x00FFFFFF) | (v << 24));

        if (isClick(mx, my, x + 4, y + 128, 132, 12)) colorPickTarget = null;
    }

    private void drawPickSlider(DrawContext ctx, String label, int x, int y, int w, int val, int mx, int my, java.util.function.IntConsumer setter) {
        ctx.fill(x, y, x + w, y + 14, 0xFF1A1A20);
        ctx.fill(x, y, x + (int) (w * val / 255f), y + 14, 0xFF3C78C8);
        ctx.drawText(textRenderer, label + " " + val, x + 4, y + 3, 0xFFFFFFFF, false);
        if (mouseDown && isClick(mx, my, x, y, w, 14)) {
            int nv = (int) ((mx - x) / (float) w * 255);
            setter.accept(Math.max(0, Math.min(255, nv)));
        }
    }

    private boolean mouseDown = false;
    private boolean isClick(int mx, int my, int x, int y, int w, int h) { return mouseDown && mx >= x && mx <= x + w && my >= y && my <= y + h; }
    private boolean isClicking(int mx, int my) { return mouseDown; }

    @Override public void renderBackground(DrawContext ctx, int mx, int my, float d) { ctx.fill(0, 0, width, height, 0x80000000); }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        mouseDown = true;
        int listX = panelX + 6, listY = panelY + 40;
        int listW = panelW - 12;
        int yy = listY - scroll;
        for (Module m : AnonClient.MODULE_MANAGER.byCategory(currentCat)) {
            int rowH = 18;
            if (mx >= listX && mx <= listX + listW && my >= yy && my <= yy + rowH) {
                if (button == 0) {
                    if (mx >= listX + listW - 16) m.expanded = !m.expanded;
                    else m.toggle();
                } else if (button == 1) m.expanded = !m.expanded;
                return true;
            }
            yy += rowH + 2;
            if (m.expanded) {
                for (Setting s : m.settings) {
                    if (s instanceof NumSetting n && mx >= listX + 8 && mx <= listX + listW - 8 && my >= yy && my <= yy + 14) dragging = n;
                    yy += 16;
                }
                yy += 2;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override public boolean mouseReleased(double mx, double my, int button) {
        mouseDown = false;
        dragging = null;
        return super.mouseReleased(mx, my, button);
    }

    @Override public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging != null) {
            int listX = panelX + 6;
            int listW = panelW - 12;
            double pct = (mx - (listX + 8)) / (double) (listW - 16);
            dragging.set(dragging.min + Math.max(0, Math.min(1, pct)) * (dragging.max - dragging.min));
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, scroll - (int) (v * 20));
        return super.mouseScrolled(mx, my, h, v);
    }

    @Override public boolean keyPressed(int key, int sc, int mod) {
        if (key == GLFW.GLFW_KEY_0 || key == GLFW.GLFW_KEY_ESCAPE) {
            MinecraftClient.getInstance().setScreen(null);
            return true;
        }
        return super.keyPressed(key, sc, mod);
    }

    @Override public boolean shouldPause() { return false; }
}
