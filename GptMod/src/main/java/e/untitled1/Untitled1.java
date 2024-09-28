package e.untitled1;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.StringArgumentType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.event.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import com.google.common.collect.Maps;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import com.theokanning.openai.OpenAiService;
import com.theokanning.openai.completion.CompletionRequest;
import com.theokanning.openai.completion.CompletionResponse;

@Mod("GptMod")
public class Untitled1 {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY"); // 从环境变量获取API Key
    private static final OpenAiService openAiService = new OpenAiService(OPENAI_API_KEY);
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(); // 创建单线程定时器

    // 存储每个玩家的对话历史及其时间戳
    private final Map<String, Pair<StringBuilder, Instant>> playerChatHistory = new ConcurrentHashMap<>();
    // 存储屏蔽词和玩家的违规次数
    private static final Set<String> BLOCKED_WORDS = Set.of("badword1", "badword2", "badword3"); // 屏蔽词列表
    private final Map<String, Integer> playerWarnings = new ConcurrentHashMap<>(); // 玩家违规计数器

    public Untitled1() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::enqueueIMC);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::processIMC);
        MinecraftForge.EVENT_BUS.register(this);

        // 定时任务：每分钟清理过期的历史记录
        scheduler.scheduleAtFixedRate(this::cleanupExpiredChatHistory, 1, 1, TimeUnit.MINUTES);
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("HELLO FROM PREINIT");
        LOGGER.info("DIRT BLOCK >> {}", Blocks.DIRT.getRegistryName());
    }

    private void enqueueIMC(final InterModEnqueueEvent event) {
        InterModComms.sendTo("untitled1", "helloworld", () -> {
            LOGGER.info("Hello world from the MDK");
            return "Hello world";
        });
    }

    private void processIMC(final InterModProcessEvent event) {
        LOGGER.info("Got IMC {}", event.getIMCStream().map(m -> m.messageSupplier().get()).collect(Collectors.toList()));
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("HELLO from server starting");
    }

    @Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class RegistryEvents {
        @SubscribeEvent
        public static void onBlocksRegistry(final RegistryEvent.Register<Block> blockRegistryEvent) {
            LOGGER.info("HELLO from Register Block");
        }
    }

    @SubscribeEvent
    public void registerCommands(Commands.CommandSelectionEvent event) {
        event.register(Commands.literal("gpt")
                        .then(Commands.argument("question", StringArgumentType.greedyString())
                                .executes(ctx -> handleGptCommand(ctx))))
                .then(Commands.literal("history").executes(ctx -> handleHistoryCommand(ctx)));
    }

    private int handleGptCommand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        String playerQuestion = StringArgumentType.getString(ctx, "question").trim();
        String playerName = source.getPlayerOrException().getName().getString();

        LOGGER.info("Player asked: {}", playerQuestion);

        // 检查问题是否包含屏蔽词
        if (containsBlockedWord(playerQuestion)) {
            handleBlockedWordViolation(source, playerName);
            return 0; // 停止处理
        }

        if (playerQuestion.isEmpty()) {
            source.sendFailure(Component.literal("问题不能为空！请重新输入。"));
            return 0;
        }

        int maxQuestionLength = getMaxQuestionLength(source.getServer());
        if (playerQuestion.length() > maxQuestionLength) {
            source.sendFailure(Component.literal("问题长度不能超过 " + maxQuestionLength + " 个字符。"));
            return 0;
        }

        source.sendSuccess(Component.literal("正在生成响应，请稍等..."), false);

        playerChatHistory.putIfAbsent(playerName, new Pair<>(new StringBuilder(), Instant.now()));

        MinecraftServer server = source.getServer();
        if (server != null) {
            CompletableFuture.supplyAsync(() -> callChatGPTAPI(playerQuestion), scheduler)
                    .thenAccept(response -> {
                        if (response != null && !response.isEmpty()) {
                            updateChatHistory(playerName, playerQuestion, response);
                            source.sendSuccess(Component.literal("GPT: " + response), false);
                            LOGGER.info("Response sent to player: {}", response);
                        } else {
                            source.sendFailure(Component.literal("没有返回任何内容！"));
                        }
                    })
                    .exceptionally(ex -> {
                        source.sendFailure(Component.literal("无法联系ChatGPT: " + ex.getMessage()));
                        LOGGER.error("Error contacting ChatGPT: ", ex);
                        return null;
                    });
        }
        return 1;
    }

    private int handleHistoryCommand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        String playerName = source.getPlayerOrException().getName().getString();
        Pair<StringBuilder, Instant> chatHistoryPair = playerChatHistory.getOrDefault(playerName, new Pair<>(new StringBuilder("没有历史记录。"), Instant.now()));

        source.sendSuccess(Component.literal("你的对话历史:\n" + chatHistoryPair.getLeft()), false);
        return 1;
    }

    private void cleanupExpiredChatHistory() {
        long currentTimeMillis = Instant.now().toEpochMilli();
        long expirationTimeMillis = currentTimeMillis - getExpirationTimeMillis();

        playerChatHistory.entrySet().removeIf(entry -> entry.getValue().getRight().toEpochMilli() < expirationTimeMillis);
    }

    private String callChatGPTAPI(String question) {
        CompletionRequest completionRequest = CompletionRequest.builder()
                .model("text-davinci-003")
                .prompt(question)
                .maxTokens(250)
                .build();

        try {
            CompletionResponse response = openAiService.createCompletion(completionRequest);
            if (response.getChoices() != null && !response.getChoices().isEmpty()) {
                return response.getChoices().get(0).getText().trim();
            } else {
                LOGGER.warn("No choices returned from the API for question: {}", question);
                return "";
            }
        } catch (Exception e) {
            LOGGER.error("Error during API call: ", e);
            return "";
        }
    }

    private void updateChatHistory(String playerName, String playerQuestion, String response) {
        Pair<StringBuilder, Instant> chatHistoryPair = playerChatHistory.get(playerName);
        StringBuilder chatHistory = chatHistoryPair.getLeft();
        chatHistory.append("你: ").append(playerQuestion).append("\n");
        chatHistory.append("GPT: ").append(response).append("\n");
        playerChatHistory.put(playerName, new Pair<>(chatHistory, Instant.now()));

        // 存储到文件
        writeChatHistoryToFile(playerName, chatHistory.toString());
    }

    private void writeChatHistoryToFile(String playerName, String chatHistory) {
        String filePath = "D:\\MinecraftChatHistory\\" + playerName + "_history.txt"; // 指定文件路径
        File file = new File(filePath);

        // 确保文件的父目录存在
        file.getParentFile().mkdirs();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
            writer.write(chatHistory);
            writer.flush();
            LOGGER.info("聊天记录已保存到文件: {}", filePath);
        } catch (IOException e) {
            LOGGER.error("保存聊天记录时出错: ", e);
        }
    }

    private boolean containsBlockedWord(String question) {
        for (String blockedWord : BLOCKED_WORDS) {
            if (question.toLowerCase().contains(blockedWord.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
/*


    private void handleBlockedWordViolation(CommandSourceStack source, String playerName) {
        int warnings = playerWarnings.getOrDefault(playerName, 0);
        warnings++;

        switch (warnings) {
            case 1:
                source.sendFailure(Component.literal("警告: 请勿使用不当语言。"));
                break;
            case 2:
                source.sendFailure(Component.literal("你已被踢出服务器，因为你使用了不当语言。"));
                source.getPlayerOrException().kick(Component.literal("你已被踢出，使用了不当语言。"));
                break;
            case 3:
                source.sendFailure(Component.literal("你已被封禁30分钟，因为你连续违反了规则。"));
                source.getPlayerOrException().disconnect(Component.literal("你已被封禁30分钟，使用了不当语言。"));
                banPlayer(playerName, 30);
                break;
            case 4:
                source.sendFailure(Component.literal("你已被封禁1小时，因为你再次违反了规则。"));
                source.getPlayerOrException().disconnect(Component.literal("你已被封禁1小时，使用了不当语言。"));
                banPlayer(playerName, 60);
                break;
            default:
                source.sendFailure(Component.literal("你已被封禁2小时，因为你多次违反了规则。"));
                source.getPlayerOrException().disconnect(Component.literal("你已被封禁2小时，使用了不当语言。"));
                banPlayer(playerName, 120); // 每次违规后封禁时间增加
                break;
        }

        playerWarnings.put(playerName, warnings); // 更新违规计数
    }

    private void banPlayer(String playerName, int durationMinutes) {
        // 这里需要实现玩家封禁的逻辑
        // 你可以使用服务器的封禁方法，或记录在文件中
        // 此处只是示例，具体实现请根据你的服务器 API 进行调整
        LOGGER.info("玩家 {} 已被封禁 {} 分钟", playerName, durationMinutes);

        // 记录封禁信息到文件
        writeBanInfoToFile(playerName, durationMinutes);

        // 这里可以通过服务器 API 来实际封禁玩家
        // server.banPlayer(playerName, durationMinutes);
    }

    private void writeBanInfoToFile(String playerName, int durationMinutes) {
        String filePath = "D:\\MinecraftChatHistory\\banned_players.txt"; // 封禁信息文件路径
        File file = new File(filePath);

        // 确保文件的父目录存在
        file.getParentFile().mkdirs();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
            String banInfo = String.format("玩家: %s 被封禁 %d 分钟 于 %s\n", playerName, durationMinutes, Instant.now());
            writer.write(banInfo);
            writer.flush();
            LOGGER.info("封禁信息已保存到文件: {}", filePath);
        } catch (IOException e) {
            LOGGER.error("保存封禁信息时出错: ", e);
        }
    }
*/
public class ChatModerationManager {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Map<String, Integer> playerWarnings = Maps.newHashMap();

    public void handleBlockedWordViolation(CommandSourceStack source, String playerName) {
        int warnings = playerWarnings.getOrDefault(playerName, 0);
        warnings++;

        switch (warnings) {
            case 1:
                source.sendFailure(Component.literal("警告: 请勿使用不当语言。"));
                break;
            case 2:
                source.sendFailure(Component.literal("你已被踢出服务器，因为你使用了不当语言。"));
                source.getPlayerOrException().kick(Component.literal("你已被踢出，使用了不当语言。"));
                break;
            case 3:
                source.sendFailure(Component.literal("你已被封禁30分钟，因为你连续违反了规则。"));
                source.getPlayerOrException().disconnect(Component.literal("你已被封禁30分钟，使用了不当语言。"));
                banPlayer(playerName, 30);
                break;
            case 4:
                source.sendFailure(Component.literal("你已被封禁1小时，因为你再次违反了规则。"));
                source.getPlayerOrException().disconnect(Component.literal("你已被封禁1小时，使用了不当语言。"));
                banPlayer(playerName, 60);
                break;
            default:
                source.sendFailure(Component.literal("你已被封禁2小时，因为你多次违反了规则。"));
                source.getPlayerOrException().disconnect(Component.literal("你已被封禁2小时，使用了不当语言。"));
                banPlayer(playerName, 120); // 每次违规后封禁时间增加
                break;
        }

        playerWarnings.put(playerName, warnings); // 更新违规计数
    }

    private void banPlayer(String playerName, int durationMinutes) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null) {
            PlayerList playerList = server.getPlayerList();
            ServerPlayer player = playerList.getPlayerByName(playerName);
            if (player != null) {
                player.disconnect(Component.literal("你已被封禁 " + durationMinutes + " 分钟，使用了不当语言。"));
                LOGGER.info("玩家 {} 已被封禁 {} 分钟", playerName, durationMinutes);

                // 记录封禁信息到文件
                writeBanInfoToFile(playerName, durationMinutes);

                // 通过服务器 API 来实际封禁玩家
                long durationSeconds = durationMinutes * 60L;
                playerList.banPlayer(playerName, "使用了不当语言", durationSeconds);
            } else {
                LOGGER.warn("未找到玩家: {}", playerName);
            }
        } else {
            LOGGER.warn("无法获取服务器实例，封禁操作失败");
        }
    }

    private void writeBanInfoToFile(String playerName, int durationMinutes) {
        String filePath = "D:\\MinecraftChatHistory\\banned_players.txt"; // 封禁信息文件路径
        File file = new File(filePath);

        // 确保文件的父目录存在
        file.getParentFile().mkdirs();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
            String banInfo = String.format("玩家: %s 被封禁 %d 分钟 于 %s\n", playerName, durationMinutes, Instant.now());
            writer.write(banInfo);
            writer.flush();
            LOGGER.info("封禁信息已保存到文件: {}", filePath);
        } catch (IOException e) {
            LOGGER.error("保存封禁信息时出错: ", e);
        }
    }
}
    private int getMaxQuestionLength(MinecraftServer server) {
        if (server == null) {
            return 250; // 默认长度
        }

        int onlinePlayers = server.getPlayerCount();
        if (onlinePlayers > 300) {
            return 100; // 在线人数超过300时限制为100个字符
        } else {
            return 250; // 默认长度
        }
    }

    private long getExpirationTimeMillis() {
        MinecraftServer server = MinecraftServer.getCurrentServer();
        if (server == null) {
            return 5 * 60 * 1000; // 默认5分钟
        }

        int onlinePlayers = server.getPlayerCount();
        if (onlinePlayers > 300) {
            return 3 * 60 * 1000; // 在线人数超过300时保留3分钟
        } else {
            return 5 * 60 * 1000; // 默认保留5分钟
        }
    }
}

// 辅助类：用于存储聊天历史和时间戳
class Pair<L, R> {
    private L left;
    private R right;

    public Pair(L left, R right) {
        this.left = left;
        this.right = right;
    }

    public L getLeft() {
        return left;
    }

    public R getRight() {
        return right;
    }
}

