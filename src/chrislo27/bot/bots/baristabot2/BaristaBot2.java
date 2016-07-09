package chrislo27.bot.bots.baristabot2;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import javax.sound.sampled.UnsupportedAudioFileException;

import com.google.code.chatterbotapi.ChatterBot;
import com.google.code.chatterbotapi.ChatterBotFactory;
import com.google.code.chatterbotapi.ChatterBotSession;
import com.google.code.chatterbotapi.ChatterBotType;

import chrislo27.bot.Main;
import chrislo27.bot.MusicDatabase;
import chrislo27.bot.bots.Bot;
import chrislo27.bot.bots.baristabot2.rhythm.RhythmGameHandler;
import chrislo27.bot.util.Utils;
import sx.blah.discord.api.IDiscordClient;
import sx.blah.discord.api.events.EventSubscriber;
import sx.blah.discord.handle.impl.events.MessageReceivedEvent;
import sx.blah.discord.handle.impl.events.ReadyEvent;
import sx.blah.discord.handle.impl.events.UserJoinEvent;
import sx.blah.discord.handle.impl.events.UserLeaveEvent;
import sx.blah.discord.handle.impl.events.UserVoiceChannelLeaveEvent;
import sx.blah.discord.handle.impl.events.UserVoiceChannelMoveEvent;
import sx.blah.discord.handle.obj.IChannel;
import sx.blah.discord.handle.obj.IMessage;
import sx.blah.discord.handle.obj.IPrivateChannel;
import sx.blah.discord.handle.obj.IUser;
import sx.blah.discord.handle.obj.IVoiceChannel;
import sx.blah.discord.handle.obj.Status;
import sx.blah.discord.util.DiscordException;
import sx.blah.discord.util.MessageBuilder;
import sx.blah.discord.util.MessageBuilder.Styles;
import sx.blah.discord.util.MissingPermissionsException;
import sx.blah.discord.util.RateLimitException;
import sx.blah.discord.util.RequestBuffer;
import sx.blah.discord.util.audio.AudioPlayer;
import sx.blah.discord.util.audio.AudioPlayer.Track;
import sx.blah.discord.util.audio.events.TrackFinishEvent;
import sx.blah.discord.util.audio.events.TrackStartEvent;

public class BaristaBot2 extends Bot {

	public static final int QUEUE_LIMIT = 25;
	public static final int RANDOM_LIMIT = 10;
	public static final String[] RESTRICTED_CHANNELS = { "general" };
	public static final String IDEAL_CHANNEL = "191731385008914432";
	public IVoiceChannel radioChannel = null;
	public AudioPlayer audioPlayer;
	protected Date startTime;
	protected double secondsPlaying = 0;
	protected long playingStartTime = System.currentTimeMillis();
	protected boolean canAddToQueue = true;

	public final CommandHandler cmdHandler;
	public RhythmGameHandler rgameHandler;

	private ChatterBot cleverbot;
	private ChatterBotSession cleverbotSession;

	private boolean debugMode = false;

	public BaristaBot2() {
		PermPrefs.instance();

		startTime = new Date();
		cmdHandler = new CommandHandler(this);

		try {
			cleverbot = new ChatterBotFactory().create(ChatterBotType.CLEVERBOT);
			cleverbotSession = cleverbot.createSession();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void setDebugging(boolean debug) {
		debugMode = true;

		if (client != null) {
			client.changePresence(debugMode);
			setStatus(null);
		}
	}

	public boolean isDebugging() {
		return debugMode;
	}

	@Override
	public void setClient(IDiscordClient c) {
		super.setClient(c);

		rgameHandler = new RhythmGameHandler(this);

		client.getDispatcher().registerListener(rgameHandler);
	}

	public MessageBuilder getNewBuilder(IChannel channel) {
		return new MessageBuilder(client).withChannel(channel);
	}

	public void sendMessage(MessageBuilder builder) {
		RequestBuffer.request(() -> {
			try {
				builder.build();
			} catch (DiscordException e) {
				Main.error("Failed to send message!");
				e.printStackTrace();
			} catch (MissingPermissionsException e) {
				Main.warn("Missing permissions to send messages!");
				e.printStackTrace();
			}
		});
	}

	@EventSubscriber
	public void onMessageGet(MessageReceivedEvent event) {
		IMessage messageObj = event.getMessage();
		IChannel channel = messageObj.getChannel();
		String message = messageObj.getContent();
		IUser author = messageObj.getAuthor();

		for (IUser mention : messageObj.getMentions()) {
			if (messageObj.mentionsEveryone()) break;
			if (mention.getID().equals(client.getOurUser().getID())) {
				long permLevel = PermPrefs.getPermissionsLevel(author.getID());

				if (permLevel < PermissionTier.NORMAL) {
					return;
				} else {
					// replace mention - two types
					// <@!ID> and <@ID>
					String content = message.replace(client.getOurUser().mention(true), "")
							.replace("<@" + client.getOurUser().getID() + ">", "");

					if (message.isEmpty()) {
						Main.info("Cleverbot question was empty");

						return;
					}

					String response = null;
					try {
						response = cleverbotSession.think(content);
					} catch (Exception e) {
						e.printStackTrace();
					}

					Main.info("Cleverbot responding: " + response);

					sendMessage(
							getNewBuilder(channel).appendContent(":speech_balloon: " + response));
				}

				return;
			}
		}

		if (!message.startsWith("%") || message.length() <= 1) return;
		if (author.isBot() && author != client.getOurUser()) return;

		String[] args = message.substring(1).split("\\s+");
		String command = args[0].toLowerCase();
		String[] commandArgs = Arrays.copyOfRange(args, 1, args.length);

		String response = cmdHandler.onCommand(command, commandArgs, messageObj, channel, author);

		if (response != null) {
			sendMessage(getNewBuilder(channel)
					.appendContent(author.mention() + " **The command failed:**  ")
					.appendContent(response));
		}

		System.gc();
	}

	@Override
	public void tickUpdate(float delta) {
		super.tickUpdate(delta);
	}

	public boolean emptyQueueIfAllGone(IChannel channel) {
		for (IUser user : radioChannel.getConnectedUsers()) {
			if (user == client.getOurUser()) continue;
			if (user.isBot()) continue;
			if (user.isDeafLocally() || user.isDeaf(radioChannel.getGuild())) continue;
			if (PermPrefs.getPermissionsLevel(user.getID()) <= PermissionTier.DUNGEON) continue;

			return false;
		}

		audioPlayer.getPlaylist().clear();
		setStatus(null);
		rgameHandler.cancelGame();

		Main.info("Cleared queue due to no one/all deaf in the radio channel");
		if (channel != null) sendMessage(getNewBuilder(channel)
				.appendContent("Cleared queue because everyone is all gone, or all deafened."));
		return true;
	}

	@EventSubscriber
	public void onAudioStart(TrackStartEvent event) {
		File f = ((File) event.getTrack().getMetadata().get("file"));

		if (f != null) {
			setStatus("♫ " + MusicDatabase.getDisguisedName(f) + " ♫");
			Main.info("Starting to play " + Utils.stripExtension(f.getName()));

			playingStartTime = System.currentTimeMillis();
		}
	}

	@EventSubscriber
	public void onAudioFinish(TrackFinishEvent event) {
		if (!event.getNewTrack().isPresent() || audioPlayer.getPlaylist().size() == 0) {
			setStatus(null);
			Main.info("Finished queue");
		}

		secondsPlaying += Math.abs(System.currentTimeMillis() - playingStartTime) / 1000.0D;

		emptyQueueIfAllGone(null);

		Main.info("Finished playing " + Utils
				.stripExtension(((File) event.getOldTrack().getMetadata().get("file")).getName()));
	}

	@EventSubscriber
	public void onUserDisconnectVoice(UserVoiceChannelLeaveEvent event) {
		emptyQueueIfAllGone(null);
	}

	@EventSubscriber
	public void onUserMoveVoice(UserVoiceChannelMoveEvent event) {
		emptyQueueIfAllGone(null);
	}

	@EventSubscriber
	public void onUserConnect(UserJoinEvent event) {

	}

	@EventSubscriber
	public void onUserDisconnect(UserLeaveEvent event) {

	}

	@EventSubscriber
	public void onMeConnect(ReadyEvent event) {
		attemptConnectToRadioChannel();
	}

	@Override
	public void onProgramExit() {
		super.onProgramExit();

		PermPrefs.instance().save();
	}

	public void attemptConnectToRadioChannel() {
		Main.info("Attempting to connect to radio audio channel...");

		IVoiceChannel radioChannel = getRadioChannel();

		if (radioChannel == null) {
			Main.warn("Radio channel not found!");
		} else {
			this.radioChannel = null;
			try {
				radioChannel.join();
				this.radioChannel = radioChannel;
				Main.info("Joined radio audio channel successfully.");
			} catch (MissingPermissionsException e) {
				Main.warn("Missing permission to join voice channel!");
				e.printStackTrace();
			}
		}

		audioPlayer = null;
		if (this.radioChannel != null) {
			audioPlayer = AudioPlayer.getAudioPlayerForGuild(this.radioChannel.getGuild());

			Main.info("Successfully gotten AudioPlayer instance");
		}

		if (audioPlayer != null) {
			audioPlayer.getPlaylist().clear();
		}
		setStatus(null);
	}

	public IVoiceChannel getRadioChannel() {
		for (IVoiceChannel channel : client.getVoiceChannels()) {
			// whitelisted servers:
			// Rhythm Heaven, BaristaBotTest
			if (!channel.getGuild().getID().equals("155833718643228672")
					&& !channel.getGuild().getID().equals("199604421955420160"))
				continue;

			if (channel.getName().equalsIgnoreCase("radio")) return channel;
		}

		return null;
	}

	public String checkMusicRestricted(IChannel channel, IUser author) {
		if (audioPlayer == null) {
			return author.mention()
					+ " The AudioPlayer is null, ask a mod to use the command `%reconnectaudio`";
		}

		if (debugMode && PermPrefs.getPermissionsLevel(author.getID()) < PermissionTier.ADMIN) {
			return author.mention()
					+ " :wrench: The bot is in debug mode, so you can't do any music actions for right now.";
		}

		if (PermPrefs.getPermissionsLevel(author.getID()) >= PermissionTier.MODERATOR) return null;

		for (String s : RESTRICTED_CHANNELS) {
			if (channel.getName().equalsIgnoreCase(s)) {
				return author.mention() + " All BaristaBot music actions should be done in <#"
						+ IDEAL_CHANNEL + ">, please. (Restricted in this current channel \""
						+ channel.getName() + "\")";
			}
		}

		return null;
	}

	public boolean canPlayMusic(IChannel channel) {
		if (radioChannel == null || radioChannel.isConnected() == false) {
			Main.info("Tried to play music, not connected to voice channel");
			sendMessage(getNewBuilder(channel).appendContent(
					"I'm not connected to the \"Radio\" channel, so I can't play music/do audio-related things."));

			return false;
		}

		if (audioPlayer == null) {
			Main.info("Audio player is null");
			sendMessage(getNewBuilder(channel).appendContent(
					"The AudioPlayer instance is null, ask a dev to reconnect it (reconnectaudio)"));

			return false;
		}

		return true;
	}

	public void showQueue(IChannel channel) {
		if (!canPlayMusic(channel)) return;

		boolean shouldBoldLimit = audioPlayer.playlistSize() >= (QUEUE_LIMIT - 5);
		String limitMsg = (shouldBoldLimit ? "**" : "") + audioPlayer.playlistSize()
				+ (shouldBoldLimit ? "**" : "") + " / " + QUEUE_LIMIT + " limit.";
		MessageBuilder builder = getNewBuilder(channel)
				.appendContent("Here's what's in the queue. ");
		builder.appendContent(limitMsg);
		if (shouldBoldLimit) {
			builder.appendContent(" ");
			if (audioPlayer.playlistSize() < QUEUE_LIMIT) {
				builder.appendContent("You're approaching the queue limit!", Styles.BOLD);
			} else if (audioPlayer.playlistSize() == QUEUE_LIMIT) {
				builder.appendContent("Queue full!", Styles.BOLD);
			} else {
				builder.appendContent("You're __over__ the queue limit!", Styles.BOLD);
			}
		}
		builder.appendContent("\n");

		for (int i = 0; i < audioPlayer.getPlaylist().size(); i++) {
			Track track = audioPlayer.getPlaylist().get(i);
			long elapsed = track.getCurrentTrackTime();
			long total = track.getTotalTrackTime();

			builder.appendContent((i == 0 ? "**" : "") + (i + 1) + ". "
					+ MusicDatabase.getDisguisedName(((File) track.getMetadata().get("file")))
					+ (i == 0 ? "**" : ""));

			if (i == 0) {
				builder.appendContent(" - "
						+ String.format("%02d:%02d", TimeUnit.MILLISECONDS.toMinutes(elapsed),
								TimeUnit.MILLISECONDS.toSeconds(elapsed) - TimeUnit.MINUTES
										.toSeconds(TimeUnit.MILLISECONDS.toMinutes(elapsed)))
						+ " elapsed");
			}

			builder.appendContent("\n");
		}

		if (audioPlayer.getPlaylist().size() == 0) {
			builder.appendContent("Nothing's here...", Styles.ITALICS);
		}

		sendMessage(builder);
	}

	public void shuffle(IChannel channel) {
		if (!canPlayMusic(channel)) return;
		if (audioPlayer.playlistSize() == 0) return;

		Track first = audioPlayer.getCurrentTrack();
		Collections.shuffle(audioPlayer.getPlaylist());
		audioPlayer.getPlaylist().remove(first);
		audioPlayer.getPlaylist().add(0, first);

		sendMessage(getNewBuilder(channel).appendContent("The playlist has been shuffled."));
		Main.info("Shuffled queue");
		showQueue(channel);

	}

	public void queueAudio(IChannel channel, File file, MessageBuilder builder) {
		if (!canPlayMusic(channel)) return;
		if (file == null) {
			builder.appendContent("The file to be queued is null!");
			Main.warn("File for queuing was null");

			return;
		}

		if (audioPlayer.playlistSize() >= QUEUE_LIMIT) {
			builder.appendContent(
					"You can't add any more songs to the playlist (maximum " + QUEUE_LIMIT + ").");
			Main.info("Hit playlist limit when attempting to add more");

			return;
		}

		if (!canAddToQueue) {
			builder.appendContent(":no_entry: The bot has disabled queue-adding.");
			Main.info("Cannot add any more to queue");

			return;
		}

		try {
			Track newTrack = audioPlayer.queue(file);
			boolean alreadyIn = false;

			for (Track track : audioPlayer.getPlaylist()) {
				if (track != newTrack && track.getMetadata().get("file")
						.equals(newTrack.getMetadata().get("file"))) {
					alreadyIn = true;
					break;
				}
			}

			String extensionless = Utils.stripExtension(file.getName());
			String disguisedName = MusicDatabase.getDisguisedName(file);

			if (!alreadyIn) {
				String th = "th";
				if (audioPlayer.getPlaylist().size() == 1) {
					th = "st";
				} else if (audioPlayer.getPlaylist().size() == 2) {
					th = "nd";
				} else if (audioPlayer.getPlaylist().size() == 3) {
					th = "rd";
				}

				Main.info("Queued " + extensionless);
				builder.appendContent("__" + disguisedName + "__ has been queued, it is "
						+ audioPlayer.getPlaylist().size() + th + ".");
			} else {
				audioPlayer.getPlaylist().remove(audioPlayer.getPlaylist().size() - 1);
				Main.info("Didn't queue " + extensionless
						+ " because it was already in the playlist");
				builder.appendContent(disguisedName + " is already in the queue!");
			}
		} catch (IOException e) {
			Main.error("IOException while queuing audio");
			builder.appendContent("An exception occurred while trying to queue audio: ")
					.appendContent(e.getMessage(), Styles.CODE);
			e.printStackTrace();
		} catch (UnsupportedAudioFileException e) {
			Main.error("Unsupported audio file");
			builder.appendContent("The audio file is unsupported. ").appendContent(e.getMessage());
			e.printStackTrace();
		}

		emptyQueueIfAllGone(channel);
	}

	public void skipTrack(IChannel channel) {
		if (!canPlayMusic(channel)) return;

		List<Track> playlist = audioPlayer.getPlaylist();
		MessageBuilder builder = getNewBuilder(channel);
		boolean showQueue = false;

		if (playlist.size() == 0) {
			builder.appendContent("There's nothing in the queue to skip!");
		} else {
			builder.appendContent("Skipping to next song...");
			audioPlayer.skip();
			Main.info("Skipped to next song");
			showQueue = true;

			secondsPlaying += Math.abs(System.currentTimeMillis() - playingStartTime) / 1000.0D;
			playingStartTime = System.currentTimeMillis();
		}

		sendMessage(builder);

		if (showQueue) {
			showQueue(channel);
		}
	}

	public void showDatabase(IChannel channel, int page, IUser user) {
		try {
			IPrivateChannel pm = client.getOrCreatePMChannel(user);

			MessageBuilder builder = getNewBuilder(pm);

			builder.appendContent("*Song Database* - Page " + (page + 1) + " / "
					+ MusicDatabase.getAllMusicPages()).appendContent("\n");
			builder.appendContent(MusicDatabase.getAllMusicList(page));

			sendMessage(builder);
		} catch (RateLimitException | DiscordException e) {
			e.printStackTrace();
		}

	}

	public boolean insertTrack(File file, int index) {
		Track t = null;
		try {
			t = audioPlayer.queue(file);
			audioPlayer.getPlaylist().remove(t);
			audioPlayer.getPlaylist().add(index, t);

			return true;
		} catch (IOException | UnsupportedAudioFileException e) {
			e.printStackTrace();
		}

		return false;
	}

	public void setStatus(String status) {
		if (debugMode) {
			client.changeStatus(Status.game("the debugging game"));
			return;
		}

		client.changeStatus(status == null ? Status.empty() : Status.game(status));
	}

}
