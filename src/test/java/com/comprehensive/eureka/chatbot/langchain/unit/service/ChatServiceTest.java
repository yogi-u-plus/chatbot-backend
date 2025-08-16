//package com.comprehensive.eureka.chatbot.langchain.integration.service;
//
//import com.comprehensive.eureka.chatbot.badword.service.BadwordServiceImpl;
//import com.comprehensive.eureka.chatbot.chatroom.entity.ChatRoom;
//import com.comprehensive.eureka.chatbot.chatroom.repository.ChatRoomRepository;
//import com.comprehensive.eureka.chatbot.client.AuthClient;
//import com.comprehensive.eureka.chatbot.client.PlanClient;
//import com.comprehensive.eureka.chatbot.client.RecommendClient;
//import com.comprehensive.eureka.chatbot.client.UserClient;
//import com.comprehensive.eureka.chatbot.langchain.dto.ChatResponseDto;
//import com.comprehensive.eureka.chatbot.langchain.dto.PlanDto;
//import com.comprehensive.eureka.chatbot.langchain.dto.RecommendPlanDto;
//import com.comprehensive.eureka.chatbot.langchain.repository.ChatMessageRepository;
//import com.comprehensive.eureka.chatbot.langchain.service.impl.ChatMemoryHandler;
//import com.comprehensive.eureka.chatbot.langchain.service.impl.ChatServiceImpl;
//import com.comprehensive.eureka.chatbot.langchain.service.impl.SentimentAnalysisService;
//import com.comprehensive.eureka.chatbot.langchain.service.impl.SessionManager;
//import com.comprehensive.eureka.chatbot.sentiment.service.PromptServiceImpl;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import dev.langchain4j.data.message.AiMessage;
//import dev.langchain4j.memory.ChatMemory;
//import dev.langchain4j.model.chat.response.ChatResponse;
//import dev.langchain4j.model.openai.OpenAiChatModel;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.boot.test.mock.mockito.MockBean;
//
//import java.util.List;
//import java.util.Optional;
//import java.util.concurrent.ConcurrentHashMap;
//
//import static org.junit.jupiter.api.Assertions.assertNotNull;
//import static org.junit.jupiter.api.Assertions.assertTrue;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.Mockito.when;
//
//@SpringBootTest
//@AutoConfigureMockMvc
//class ChatServiceImplTest {
//
//    @Autowired
//    private ChatServiceImpl chatServiceImpl;
//
//    @MockBean
//    private ChatRoomRepository chatRoomRepository;
//    @MockBean
//    private ChatMessageRepository chatMessageRepository;
//    @MockBean
//    private SentimentAnalysisService sentimentAnalysisService;
//    @MockBean
//    private ChatMemoryHandler chatMemoryHandler;
//    @MockBean
//    private UserClient userClient;
//    @MockBean
//    private PlanClient planClient;
//    @MockBean
//    private ObjectMapper objectMapper;
//    @MockBean
//    private SessionManager sessionManager;
//    @MockBean
//    private RecommendClient recommendClient;
//    @MockBean
//    private AuthClient authClient;
//    @MockBean
//    private BadwordServiceImpl badWordService;
//    @MockBean
//    private PromptServiceImpl promptService;
//
//    @MockBean
//    private OpenAiChatModel baseOpenAiModel;
//
//    private final Long USER_ID = 1L;
//    private final Long CHATROOM_ID = 1L;
//    private final String MESSAGE = "요금제 추천해줘";
//
//    @BeforeEach
//    void setup() {
//        ChatRoom chatRoom = new ChatRoom();
//        when(chatRoomRepository.findById(CHATROOM_ID)).thenReturn(Optional.of(chatRoom));
//
//        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
//
//        ChatMemory memory = new ChatMemory();
//        when(chatMemoryHandler.getMemoryOfChatRoom(CHATROOM_ID)).thenReturn(memory);
//        when(sessionManager.getPromptProcessing()).thenReturn(new ConcurrentHashMap<>());
//
//        // 기본 응답 설정
//        when(baseOpenAiModel.doChat(any())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.builder().text("[prompt전환]3번으로 예상").build()).build());
//
//    }
//
//    @Test
//    void test_badword_detected() throws Exception {
//        when(badWordService.checkBadWord(any())).thenReturn(true);
//
//        ChatResponseDto response = chatServiceImpl.generateReply(USER_ID, CHATROOM_ID, "욕설 포함된 메시지");
//
//        assertTrue(response.getMessage().contains("금지된 단어"));
//    }
//
//    @Test
//    void test_switch_prompt_detected() throws Exception {
//        when(sentimentAnalysisService.analysisSentiment(any())).thenReturn("기쁨");
//        when(baseOpenAiModel.doChat(any())).thenReturn("[prompt전환]3번으로 예상").thenReturn("추천할게요");
//
//        ChatResponseDto response = chatServiceImpl.generateReply(USER_ID, CHATROOM_ID, MESSAGE);
//
//        assertTrue(response.getMessage().contains("추천할게요"));
//    }
//
//    @Test
//    void test_end_signal_detected() throws Exception {
//        when(sentimentAnalysisService.analysisSentiment(any())).thenReturn("기쁨");
//        when(baseOpenAiModel.generate(any())).thenReturn("[END_OF_FUNNYCHAT_SCENARIO]");
//
//        ChatResponseDto response = chatServiceImpl.generateReply(USER_ID, CHATROOM_ID, MESSAGE);
//
//        assertNotNull(response.getMessage());
//    }
//
//    @Test
//    void test_user_password_ready() throws Exception {
//        when(baseOpenAiModel.generate(any())).thenReturn("[사용자 비밀번호 준비 완료]");
//        GetUserProfileDetailResponseDto userProfile = new GetUserProfileDetailResponseDto();
//        when(userClient.getUserProfile(any())).thenReturn(new ResponseWrapper<>(userProfile));
//        when(chatMessageRepository.findTopByOrderByIdDesc()).thenReturn(new ChatMessage());
//
//        ChatResponseDto response = chatServiceImpl.generateReply(USER_ID, CHATROOM_ID, MESSAGE);
//
//        assertTrue(response.getMessage().contains("어떤 도움을 드릴까요"));
//    }
//
//    @Test
//    void test_plan_query_start() throws Exception {
//        when(baseOpenAiModel.generate(any())).thenReturn("요금제 조회-요금제 혜택-음악");
//
//        ChatResponseDto response = chatServiceImpl.generateReply(USER_ID, CHATROOM_ID, MESSAGE);
//
//        assertNotNull(response.getMessage());
//    }
//
//    @Test
//    void test_keyword_extraction_and_recommendation() throws Exception {
//        when(baseOpenAiModel.doChat(any()))
//                .thenReturn("직업을 확인하였습니다")
//                .thenReturn("운동")
//                .thenReturn("추천 이유입니다");
//        when(sentimentAnalysisService.analysisSentiment(any())).thenReturn("기쁨");
//        when(recommendClient.recommendByKeyword(any(), any())).thenReturn(
//                new ResponseWrapper<>(List.of(
//                        new RecommendPlanDto(new PlanDto("요금제A", 5000, 10, "GB", "30일", 1, "GB", 100, 50, true, "엔터"))
//                )));
//
//        ChatResponseDto response = chatServiceImpl.generateReply(USER_ID, CHATROOM_ID, MESSAGE);
//
//        assertTrue(response.isRecommended());
//        assertTrue(response.getMessage().contains("요금제"));
//    }
//}
