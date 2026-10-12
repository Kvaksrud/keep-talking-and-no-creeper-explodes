package no.kvaksrud.nocreeperexplodes.client;

import com.mojang.blaze3d.platform.InputConstants;
import no.kvaksrud.nocreeperexplodes.DefuseSession;
import no.kvaksrud.nocreeperexplodes.NoCreeperExplodes;
import no.kvaksrud.nocreeperexplodes.network.CutWirePayload;
import org.joml.Vector2i;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.monster.Creeper;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The bomb panel: three wires to choose from. Closing it early (Esc, or getting out of reach) walks away from
 * the bomb, which sets it off. Everything is drawn from wire_cut.png,
 * whose layout comes from tools/gen_textures.py; the constants below mirror it.
 */
public class WireCutScreen extends Screen {
	private static final Identifier SHEET = NoCreeperExplodes.id("textures/gui/wire_cut.png");
	private static final int SHEET_SIZE = 256;
	private static final int PANEL_W = 176;
	private static final int PANEL_H = 112;
	private static final int WIRE_X = 20;
	private static final int WIRE_W = 126;
	private static final int WIRE_H = 10;
	private static final int WIRE_SHEET_Y = 116;
	private static final int[] WIRE_ROWS = {32, 54, 76};
	private static final int WIRE_HIT_HALF_HEIGHT = 6;
	private static final int LCD_TEXT_Y = 6;
	private static final int LED_X = PANEL_W / 2 + 13;
	private static final int LED_Y = 8;
	private static final int LED_SIZE = 3;
	private static final int BEEP_FLASH_TICKS = 2;

	private static final int VARIANT_INTACT = 0;
	private static final int VARIANT_HOVER = 1;
	private static final int VARIANT_CUT = 2;

	private static final int LCD_RED = 0xFFFF4A3A;
	private static final int LCD_GREEN = 0xFF5CFF6A;
	private static final int LED_OFF = 0xFF4A1210;
	private static final int LED_ON = 0xFFFF2A1A;
	private static final int TEXT_WHITE = 0xFFFFFFFF;
	private static final int TEXT_HINT = 0xFFD8D8D8;
	private static final int TEXT_GOOD = 0xFF7CFC7C;
	private static final int TEXT_BAD = 0xFFFF5A4A;

	/** How long "Defused!" stays up before the panel closes. */
	private static final int DEFUSED_LINGER_TICKS = 30;
	/** If the server never answers a cut (packet lost, creeper unloaded), give up after this. */
	private static final int CUT_TIMEOUT_TICKS = 40;
	/** Same idea once the timer hits zero: the server blows it up, this is only a fallback. */
	private static final int TIME_UP_TIMEOUT_TICKS = 60;

	private static final Component HINT = Component.translatable("screen.nocreeperexplodes.hint");
	private static final Component SNIP = Component.translatable("screen.nocreeperexplodes.snip");
	private static final Component DEFUSED = Component.translatable("screen.nocreeperexplodes.defused");
	private static final Component TIME_UP = Component.translatable("screen.nocreeperexplodes.time_up");

	private final int creeperId;
	private final double defuseDistance;
	private int left;
	private int top;
	private int age;
	private int cutWire = -1;
	private int ageAtCut;
	private int defusedFor = -1;
	private int nextBeepAge;
	private int flashTicks;

	public WireCutScreen(int creeperId, double defuseDistance) {
		super(Component.translatable("screen.nocreeperexplodes.title"));
		this.creeperId = creeperId;
		this.defuseDistance = defuseDistance;
	}

	@Override
	protected void init() {
		this.left = (this.width - PANEL_W) / 2;
		this.top = (this.height - PANEL_H) / 2;
	}

	@Override
	public boolean isPauseScreen() {
		// The creeper holds its fuse while you work, but the world keeps going.
		return false;
	}

	@Override
	public boolean isInGameUi() {
		return true;
	}

	private @Nullable Creeper creeper() {
		if (this.minecraft.level != null && this.minecraft.level.getEntity(this.creeperId) instanceof Creeper creeper && creeper.isAlive()) {
			return creeper;
		}

		return null;
	}

	@Override
	public void tick() {
		this.holdPlayerStill();
		this.tickBeepLight();
		this.age++;
		Creeper creeper = this.creeper();

		if (creeper == null) {
			// Wrong wire (it blew up), or it died or unloaded some other way.
			this.onClose();
			return;
		}

		if (this.defusedFor < 0 && this.cutWire >= 0 && NoCreeperExplodes.isDefused(creeper)) {
			this.defusedFor = 0;
		}

		if (this.defusedFor >= 0) {
			if (++this.defusedFor > DEFUSED_LINGER_TICKS) {
				this.onClose();
			}

			return;
		}

		if (this.cutWire >= 0) {
			if (this.age - this.ageAtCut > CUT_TIMEOUT_TICKS) {
				this.onClose();
			}

			return;
		}

		boolean inRange = this.minecraft.player != null
				&& NoCreeperExplodes.isWithinReach(this.minecraft.player, creeper, this.defuseDistance + NoCreeperExplodes.REACH_TOLERANCE);

		if (!inRange || this.age >= DefuseSession.SESSION_TICKS + TIME_UP_TIMEOUT_TICKS) {
			this.onClose();
		}
	}

	/** Movement keys don't reach the game while the panel is open; this also kills any leftover momentum. */
	private void holdPlayerStill() {
		if (this.minecraft.player != null && this.defusedFor < 0) {
			this.minecraft.player.setDeltaMovement(0.0, this.minecraft.player.getDeltaMovement().y, 0.0);
		}
	}

	/** Flashes the LED in step with the server's beeps (same schedule, counted from when the panel opened). */
	private void tickBeepLight() {
		if (this.flashTicks > 0) {
			this.flashTicks--;
		}

		if (this.cutWire < 0 && this.age >= this.nextBeepAge) {
			this.flashTicks = BEEP_FLASH_TICKS;
			this.nextBeepAge = this.age + DefuseSession.timerBeepInterval(DefuseSession.SESSION_TICKS - this.age);
		}
	}

	@Override
	public void removed() {
		if (this.cutWire < 0 && !this.isTimeUp() && ClientPlayNetworking.canSend(CutWirePayload.TYPE)) {
			// Esc or out of reach with time left: walking away from the bomb, which sets it off in a few seconds.
			// Once the timer is at zero the server blows it up anyway.
			ClientPlayNetworking.send(new CutWirePayload(this.creeperId, CutWirePayload.CANCEL));
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// Not a literal 0: 26.3 moved from GLFW to SDL, where the left button is 1.
		if (this.cutWire < 0 && !this.isTimeUp() && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			int wire = this.wireAt(event.x(), event.y());

			if (wire >= 0) {
				this.cutWire = wire;
				this.ageAtCut = this.age;
				this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.SHEARS_SNIP, 1.0F));
				ClientPlayNetworking.send(new CutWirePayload(this.creeperId, wire));
				return true;
			}
		}

		return super.mouseClicked(event, doubleClick);
	}

	private boolean isTimeUp() {
		return this.cutWire < 0 && this.age >= DefuseSession.SESSION_TICKS;
	}

	/** Middle of a wire, in GUI coordinates. */
	public Vector2i wireCenter(int wire) {
		return new Vector2i(this.left + WIRE_X + WIRE_W / 2, this.top + WIRE_ROWS[wire]);
	}

	private int wireAt(double mouseX, double mouseY) {
		double x = mouseX - this.left;

		if (x < WIRE_X || x >= WIRE_X + WIRE_W) {
			return -1;
		}

		for (int i = 0; i < WIRE_ROWS.length; i++) {
			if (Math.abs(mouseY - (this.top + WIRE_ROWS[i])) <= WIRE_HIT_HALF_HEIGHT) {
				return i;
			}
		}

		return -1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);

		graphics.blit(RenderPipelines.GUI_TEXTURED, SHEET, this.left, this.top, 0, 0, PANEL_W, PANEL_H, SHEET_SIZE, SHEET_SIZE);

		int hovered = this.cutWire < 0 && !this.isTimeUp() ? this.wireAt(mouseX, mouseY) : -1;

		for (int i = 0; i < WIRE_ROWS.length; i++) {
			int variant = i == this.cutWire ? VARIANT_CUT : i == hovered ? VARIANT_HOVER : VARIANT_INTACT;
			int v = WIRE_SHEET_Y + (i * 3 + variant) * WIRE_H;
			graphics.blit(RenderPipelines.GUI_TEXTURED, SHEET, this.left + WIRE_X, this.top + WIRE_ROWS[i] - WIRE_H / 2, 0, v, WIRE_W, WIRE_H, SHEET_SIZE, SHEET_SIZE);
		}

		this.extractTimer(graphics);

		graphics.centeredText(this.font, this.title, this.width / 2, this.top - 14, TEXT_WHITE);
		Component status = this.defusedFor >= 0 ? DEFUSED : this.cutWire >= 0 ? SNIP : this.isTimeUp() ? TIME_UP : HINT;
		int statusColor = this.defusedFor >= 0 ? TEXT_GOOD : this.isTimeUp() ? TEXT_BAD : TEXT_HINT;
		graphics.centeredText(this.font, status, this.width / 2, this.top + PANEL_H + 6, statusColor);
	}

	private void extractTimer(GuiGraphicsExtractor graphics) {
		int x = this.left + PANEL_W / 2;
		int y = this.top + LCD_TEXT_Y;

		if (this.defusedFor >= 0) {
			graphics.centeredText(this.font, "SAFE", x, y, LCD_GREEN);
			return;
		}

		graphics.fill(this.left + LED_X, this.top + LED_Y, this.left + LED_X + LED_SIZE, this.top + LED_Y + LED_SIZE, this.flashTicks > 0 ? LED_ON : LED_OFF);

		int elapsed = this.cutWire >= 0 ? this.ageAtCut : this.age;
		int seconds = Math.max(0, (DefuseSession.SESSION_TICKS - elapsed + 19) / 20);
		boolean blinkOff = seconds <= 5 && this.cutWire < 0 && (this.age / 5) % 2 == 1;

		if (!blinkOff) {
			graphics.centeredText(this.font, String.format("%d:%02d", seconds / 60, seconds % 60), x, y, LCD_RED);
		}
	}
}
