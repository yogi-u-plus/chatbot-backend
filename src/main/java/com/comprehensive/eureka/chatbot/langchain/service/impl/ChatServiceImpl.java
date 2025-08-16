package com.comprehensive.eureka.chatbot.langchain.service.impl;

import com.comprehensive.eureka.chatbot.badword.service.BadwordServiceImpl;
import com.comprehensive.eureka.chatbot.chatroom.entity.ChatRoom;
import com.comprehensive.eureka.chatbot.chatroom.repository.ChatRoomRepository;
import com.comprehensive.eureka.chatbot.client.AuthClient;
import com.comprehensive.eureka.chatbot.client.PlanClient;
import com.comprehensive.eureka.chatbot.client.RecommendClient;
import com.comprehensive.eureka.chatbot.client.UserClient;
import com.comprehensive.eureka.chatbot.client.dto.request.GetByIdRequestDto;
import com.comprehensive.eureka.chatbot.client.dto.request.LoginUserRequestDto;
import com.comprehensive.eureka.chatbot.client.dto.response.FilterListResponseDto;
import com.comprehensive.eureka.chatbot.client.dto.response.GetUserProfileDetailResponseDto;
import com.comprehensive.eureka.chatbot.client.dto.response.LoginUserResponseDto;
import com.comprehensive.eureka.chatbot.common.dto.BaseResponseDto;
import com.comprehensive.eureka.chatbot.common.exception.ChatException;
import com.comprehensive.eureka.chatbot.common.exception.ErrorCode;
import com.comprehensive.eureka.chatbot.langchain.dto.*;
import com.comprehensive.eureka.chatbot.langchain.entity.ChatMessage;
import com.comprehensive.eureka.chatbot.langchain.repository.ChatMessageRepository;
import com.comprehensive.eureka.chatbot.langchain.service.ChatService;
import com.comprehensive.eureka.chatbot.sentiment.service.PromptServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.chain.ConversationalChain;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private Environment environment;
    private final OpenAiChatModel baseOpenAiModel;
    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;
    private final ChatRoomRepository chatRoomRepository;
    private final SentimentAnalysisService sentimentAnalysisService;
    private final ChatMemoryHandler chatMemoryHandler;

    private final RecommendClient recommendClient;
    private final UserClient userClient;
    private final PlanClient planClient;
    private final AuthClient authClient;

    private String recommendPrompt;
    private String funnyChatPrompt;
    private String whattodoPrompt;
    private String userPasswordPrompt;
    private String jsonExtractionPrompt;
    private String keywordExtractionPrompt;
    private String recommendReasonPrompt;
    private String planPrompt;
    private String feedbackPrompt;
    private final BadwordServiceImpl badWordService;
    private final PromptServiceImpl promptService;

    private final SessionManager sessionManager;

    private final Map<Long, Boolean> firstChatActivated = new ConcurrentHashMap<>();
    private final Map<Long, ConversationalChain> chainMap = new ConcurrentHashMap<>();

    Map<String, String> promptMap;
    RecommendationResponseDto recommendationResponseDto;
    List<RecommendPlanDto> recommendPlans;
    Map<String, String> endSignalMap;
    Set<String> removeTarget;
    ConversationalChain chain;
    String extractedKeyword;
    @PostConstruct
    public void loadPrompts() {
        try {

            Resource systemResource = new ClassPathResource("prompts/recommend-prompt.txt");
            try (InputStream in = systemResource.getInputStream()) {
                this.recommendPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            Resource systemResource2 = new ClassPathResource("prompts/user-password-prompt.txt");
            try (InputStream in = systemResource2.getInputStream()) {
                this.userPasswordPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            Resource systemResource3 = new ClassPathResource("prompts/funnychat-prompt.txt");
            try (InputStream in = systemResource3.getInputStream()) {
                this.funnyChatPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            Resource systemResource4 = new ClassPathResource("prompts/whattodo-prompt.txt");
            try (InputStream in = systemResource4.getInputStream()) {
                this.whattodoPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            Resource systemResource5 = new ClassPathResource("prompts/plan-prompt.txt");
            try (InputStream in = systemResource5.getInputStream()) {
                this.planPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Resource systemResource6 = new ClassPathResource("prompts/feedback-prompt.txt");
            try (InputStream in = systemResource6.getInputStream()) {
                this.feedbackPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }


            Resource jsonResource = new ClassPathResource("prompts/json-extraction-prompt.txt");
            try (InputStream in = jsonResource.getInputStream()) {
                this.jsonExtractionPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            Resource keywordResource = new ClassPathResource("prompts/keyword-prompt.txt");
            try (InputStream in = keywordResource.getInputStream()) {
                this.keywordExtractionPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            this.promptMap = Map.of(
                    "[prompt전환]1번으로 예상", userPasswordPrompt,
                    "[prompt전환]2번으로 예상", funnyChatPrompt,
                    "[prompt전환]3번으로 예상", recommendPrompt,
                    "[prompt전환]4번으로 예상", planPrompt
            );

            this.endSignalMap = Map.of(
                    "사용자 정보 제공 준비 끝", "사용자 정보 제공",
                    "[END_OF_FUNNYCHAT_SCENARIO]", "심심풀이",
                    "[요금제 조회 끝]","요금제 조회",
                    "[요금제 추천 끝]","피드백",
                    "[사용자 검증 끝]","피드백"
            );

            this.removeTarget = new HashSet<>(Arrays.asList(
                   "사용자 비밀번호 준비 완료","[사용자 비밀번호 준비 완료]", "요금제 조회-","요금제 조회 준비","[요금제 조회 준비 완료]","[요금제 추천 끝]","[요금제 조회 끝]","[prompt전환]", "직업을 확인하였습니다", "키워드를 확인하였습니다", "통신성향을 모두 파악했습니다","[END_OF_FUNNYCHAT_SCENARIO]","사용자 정보 제공 준비 끝","feedbackCode","요금제 조회 끝","요금제 추천 끝"
            ));
            this.extractedKeyword = null;
            this.chain = ConversationalChain.builder()
                    .chatModel(baseOpenAiModel)
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException("프롬프트 로드 중 오류 발생", e);
        }
    }


    @Override
    public ChatResponseDto generateReply(Long userId, Long chatRoomId, String message) throws JsonProcessingException {

        // 전역적으로 사용할 리턴 변수 생성
        ChatResponseDto chatResponseDto = ChatResponseDto.of("", chatRoomId, userId);
        // 요청값 출력
        log.info("generateReply 메서드 호출됨. 사용자 ID: {}, 채팅방 ID: {}, 메시지: {}", userId, chatRoomId, message);
        // ChatRoom 조회 부분
        ChatRoom currentChatRoom = chatRoomRepository.findById(chatRoomId).orElseThrow(
                () -> new IllegalArgumentException("채팅방을 찾을 수 없습니다. ID: " + chatRoomId)
        );
        // 사용자 메시지 저장
        ChatMessageDto chatMessageDto = saveChatMessage(userId, currentChatRoom, message, false, false,false, "mock reason");
        log.info("사용자 메시지를 저장했습니다 id : " + chatMessageDto.getMessageId());
        // 금칙어 필터링 작업
        if (badWordCheck(userId, chatMessageDto.getMessage(),chatMessageDto.getTimestamp())) {
            log.info("금칙어가 발견 되었습니다.");
            saveChatMessage(userId, currentChatRoom, "사용하신 메시지에 금지된 단어가 포함되어 있습니다.", true, false,false, "mock reason");
            return ChatResponseDto.fail("사용하신 메시지에 금지된 단어가 포함되어 있습니다.", chatResponseDto);
        }


        // 감정 분석, 태도 설정
        String sentiment = sentimentAnalysisService.analysisSentiment(message);
//        String attitude = promptService.getPromptBySentimentName(sentiment).getScenario();
        // 채팅방 마다 다른 memory 가져오기
        ChatMemory memory = chatMemoryHandler.getMemoryOfChatRoom(chatRoomId);

        sessionManager.getPromptProcessing().putIfAbsent(chatRoomId, false);
        firstChatActivated.putIfAbsent(chatRoomId, true);

        //기본 : what to do prompt
        if (!sessionManager.getPromptProcessing().get(chatRoomId) || firstChatActivated.get(chatRoomId)) {
            firstChatActivated.put(chatRoomId, false);
            memory.clear();
            memory.add(SystemMessage.from(whattodoPrompt));
            log.info("대화의 첫 부분이거나, 새로운 task의 시작이므로 whattodo prompt를 실행했습니다");
        }

        // 설정한 prompt 저장된 memory 대로 채팅방 별로 lang chain 생성 하거나 반환
        ConversationalChain chain = chainMap.computeIfAbsent(chatRoomId, id ->
                ConversationalChain.builder()
                        .chatModel(baseOpenAiModel)
                        .chatMemory(memory)
                        .build()
        );
        log.info("langchain 생성 완료");


        // GPT 응답 -> 기본,처음 : whattodoprompt , task 중 : 해당prompt
        String response = chain.execute(message);
        log.info("gpt의 응답 response " + response);

        boolean shouldSave = removeTarget.stream().noneMatch(response::contains);
        if (shouldSave) {
            saveChatMessage(userId, currentChatRoom, response, true, false, false,"mock reason");
        }

        // whattodo prompt의 결과 처리(prompt directing)
        Optional<String> switchKey = detectPromptSwitch(response);
        if (switchKey.isPresent()) {
            String prompt = promptMap.get(switchKey.get());
            log.info("{} 감지됨", switchKey.get());
            memory.clear();
            memory.add(SystemMessage.from(prompt));
            sessionManager.getPromptProcessing().put(chatRoomId, true);
            response = chain.execute(message);
            log.info("바뀐 프롬프트의 첫 response"+response);
            // gpt가 사용자의 응답을 듣고, task가 끝이라고 판단 했을 때 내는 trigger 문장들은  저장 x -> 뒤에서 whattodo prompt의 결과로 변경 후 저장
            shouldSave = removeTarget.stream().noneMatch(response::contains);
            if (shouldSave) {
                saveChatMessage(userId, currentChatRoom, response, true, false, false,"mock reason");//todo
            }
        } else if (response.contains("[prompt전환]5번으로 예상")) {
            sessionManager.getPromptProcessing().put(chatRoomId, false);
            response = "어떤 도움을 드릴까요? {요금제 추천, 요금제 조회, 사용자 정보 알기, 심심풀이} 선택해주세요! ";
            saveChatMessage(userId, currentChatRoom, response, true, false, false,"mock reason");
            return ChatResponseDto.fail(response, chatResponseDto);
        }

        // Prompt 종료 탐지
        Optional<String> endSignalKey = detectEndSignal(response);
        if (endSignalKey.isPresent()) {
            String context = endSignalMap.get(endSignalKey.get());
            return endPromptAndRespond(context, chatRoomId, userId,currentChatRoom,memory,whattodoPrompt);
        }


        //지금부터는 gpt의 답변을 중간에 가로 채서 서버 처리 해야 하는 경우를 처리합니다. ( 1. 사용자의 비밀번호가 준비 됐을 때-> api 호출 후 return) , ( 2. 요금제 추천 준비가 됐을 때 -> api 호출 ), (3.키워드 감지 ) (4. 피드백 감지 -> recommend api호출)
        if(response.contains("[사용자 비밀번호 준비 완료]")){

            GetByIdRequestDto getByIdRequestDto = new GetByIdRequestDto(userId);

            GetUserProfileDetailResponseDto getUserProfileDetailResponseDto = userClient.getUserProfile(getByIdRequestDto).getData();


            sessionManager.getPromptProcessing().put(chatRoomId, false); //이 prompt 를 종료시키고 다시 whattodo로
            chatResponseDto = ChatResponseDto.builder()
                    .messageId(chatMessageRepository.findTopByOrderByIdDesc().getId())
                    .userId(userId)
                    .chatRoomId(chatRoomId)
                    .message(getUserProfileDetailResponseDto.toString() + "\n 어떤 도움을 드릴까요? {요금제 추천, 요금제 조회, 사용자 정보 알기, 심심풀이} 선택해주세요!  ")
                    .isBot(true)
                    .isRecommended(false)
                    .recommendationReason("mock reason")
                    .build();
            // gpt가 사용자의 응답을 듣고, task가 끝이라고 판단 했을 때 내는 trigger 문장들은  저장 x -> 뒤에서 whattodo prompt의 결과로 변경 후 저장
            shouldSave = removeTarget.stream().noneMatch(chatResponseDto.getMessage()::contains);
            if (shouldSave) {
                saveChatMessage(userId, currentChatRoom, chatResponseDto.getMessage(), true, false, false,"mock reason");
            }
            return chatResponseDto;
        }

        if(response.contains("[요금제 조회 준비 완료]")){
            log.info(response);
            chatResponseDto =  showPlansByBenefit("");
            // gpt가 사용자의 응답을 듣고, task가 끝이라고 판단 했을 때 내는 trigger 문장들은  저장 x -> 뒤에서 whattodo prompt의 결과로 변경 후 저장
            shouldSave = removeTarget.stream().noneMatch(chatResponseDto.getMessage()::contains);
            if (shouldSave) {
                saveChatMessage(userId, currentChatRoom, chatResponseDto.getMessage(), true, false, true,"mock reason");
            }
            return chatResponseDto;
        }

        if(response.contains("요금제 조회-")){
            log.info("요금제 조회 시작");
            String[] parts = response.split("-");
            String division = parts.length >= 3 ? parts[1] : "";
            String value = parts[2].split(" ")[0];
            log.info("가운데 단어: " + division +"마지막 단어: " + value );
            chatResponseDto = switch (division) {
                case "요금제 혜택" -> showPlansByBenefit(value);
                case "요금제 카테고리" -> showPlansByCategory(value);
                default -> chatResponseDto;
            };
            shouldSave = removeTarget.stream().noneMatch(chatResponseDto.getMessage()::contains);
            if (shouldSave) {
                saveChatMessage(userId, currentChatRoom, chatResponseDto.getMessage(), true, false, true, "mock reason");
            }
            return chatResponseDto;

        }


        if (response.contains("직업을 확인하였습니다") || response.contains("키워드를 확인하였습니다")) {

            final int MAX_RETRIES = 2;
            int attempt = 0;
            boolean validKeyword = false;

            while (attempt < MAX_RETRIES) {
                this.extractedKeyword = chain.execute(keywordExtractionPrompt);
                if (this.extractedKeyword != null && !extractedKeyword.isBlank()) {
                    validKeyword = true;
                    break;
                }
                attempt++;
            }

            if (!validKeyword) {
                return ChatResponseDto.of("키워드 추출 중 오류가 발생했습니다. 다시 시도해 주세요.", chatRoomId, userId);
            }


            log.info("extractedKeyword : {}", this.extractedKeyword);
            recommendPlans = sendKeywordToRecommendationModule(this.extractedKeyword);
            if (recommendPlans == null || recommendPlans.isEmpty()) {
                return ChatResponseDto.of("추천드릴 요금제를 찾지 못했습니다. 다른 키워드로 다시 시도해 주세요.", chatRoomId, userId);
            }
            memory.clear();
            memory.add(SystemMessage.from(this.extractedKeyword+"을 위한 요금제로 어떤 요금제를 추천해 줬어요. 사용자가 그 요금제를 알려주면, 챗봇이 그 요금제를 추천한 이유가 무엇일지 예상해서 당신이 추천한 것처럼 대답하세요 한문장으로 정리해주세요. 현재 시제로 정리하세요"));
            String reason = chain.execute(recommendPlans.get(0).toString());
            log.info("추천 이유 : " + reason);
            memory.clear();
            memory.add(SystemMessage.from(feedbackPrompt));
            String finalReply = "고객님께 다음 요금제들을 추천해 드립니다. ["+reason+"]\n\n" +
                    recommendPlans.stream()
                            .map(recommend -> {
                                PlanDto plan = recommend.getPlan();
                                return String.format(
                                        "요금제: '%s'\n" +
                                                "- 월정액: %d원\n" +
                                                "- 제공량: %d %s\n" +
                                                "- 제공 기간: %s\n" +
                                                "- 테더링 데이터: %d %s\n" +
                                                "- 음성 통화량: %d분\n" +
                                                "- 추가 통화 허용량: %d분\n" +
                                                "- 가족 결합 가능: %s\n" +
                                                "- 카테고리: %s\n",
                                        plan.getPlanName(),
                                        plan.getMonthlyFee(),
                                        plan.getDataAllowance(),
                                        plan.getDataAllowanceUnit(),
                                        plan.getDataPeriod(),
                                        plan.getTetheringDataAmount(),
                                        plan.getTetheringDataUnit(),
                                        plan.getVoiceCallAmount(),
                                        plan.getAdditionalCallAllowance(),
                                        plan.isFamilyDataEnabled() ? "가능" : "불가능",
                                        plan.getPlanCategory()
                                );
                            })
                            .collect(Collectors.joining("\n"));


            finalReply += "'\n 이 요금제에 대해서 평가 해주세요! 가격, 데이터, 부가서비스 등 만족하시나요? 아니라면, 어떤 게 마음에 안드시는 지 알려주세요! 끝내셔도 됩니다.";
            saveChatMessage(userId, currentChatRoom, finalReply, true, true,false, reason);
            return ChatResponseDto.builder()
                    .messageId(chatMessageRepository.findTopByOrderByIdDesc().getId())
                    .userId(userId)
                    .chatRoomId(chatRoomId)
                    .message(finalReply)
                    .isBot(true)
                    .isRecommended(true)
                    .recommendationReason(reason)
                    .build();
        }


        // 통신성향 수집 완료 신호 감지
        if (response.contains("통신성향을 모두 파악했습니다")) {
            this.extractedKeyword = null;
            log.info(this.extractedKeyword);
            JsonNode root = null;
            String rawJson;
            final int MAX_RETRIES = 2;
            int attempt = 0;
            boolean valid = false;
            while (attempt < MAX_RETRIES) {
                rawJson = chain.execute(jsonExtractionPrompt);

                log.info("rawJson 브라켓 추출하기 전: {}", rawJson);
                // START: 대괄호 안 혜택 추출 로직 추가
                int bracketStart = rawJson.indexOf('[');
                int bracketEnd = rawJson.indexOf(']');
                String premiumBenefit = null;
                String mediaBenefit = null;
                if (bracketStart >= 0 && bracketEnd > bracketStart) {
                    String bracketContent = rawJson.substring(bracketStart + 1, bracketEnd);
                    String[] parts = bracketContent.split("\\|");
                    if (parts.length > 0) premiumBenefit = parts[0].trim();
                    if (parts.length > 1) mediaBenefit = parts[1].trim();
                }
                BenefitRequestDto benefitDto = new BenefitRequestDto();
                benefitDto.setPremium(premiumBenefit);
                benefitDto.setMedia(mediaBenefit);
                log.info("premiumBenefit : {}", premiumBenefit);
                log.info("mediaBenefit : {}", mediaBenefit);

                // PlanClient로 혜택 그룹 ID 조회
                Long benefitGroupId = planClient.getBenefitIds(benefitDto);
                log.info("BenefitGroupId: {}", benefitGroupId);


                int start = rawJson.indexOf('{');
                int end = rawJson.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    rawJson = rawJson.substring(start, end + 1);
                } else {
                    log.warn("JSON 추출 실패: 유효한 JSON 객체 형식이 아닙니다.");
                    attempt++;
                    continue;
                }
                log.info("rawJson : {}", rawJson);
                root = objectMapper.readTree(rawJson);
                // UserPreferenceDto 필드 유효성 검사

                if (root instanceof ObjectNode) {
                    ((ObjectNode) root).put("preferenceBenefitGroupId", benefitGroupId.intValue());
                }
                log.info("root : {}", root);

                if (root.get("preferenceDataUsage").isInt()
                        && root.get("preferenceDataUsageUnit").isTextual()
                        && root.get("preferenceSharedDataUsage").isInt()
                        && root.get("preferenceSharedDataUsageUnit").isTextual()
                        && root.get("preferencePrice").isInt()
                        && root.get("preferenceBenefitGroupId").isInt()
                        && root.get("isPreferenceFamilyData").isBoolean()
                        && root.get("preferenceValueAddedCallUsage").isInt()) {
                    valid = true;
                    break;
                }
                attempt++;
            }

            log.info("final root : {}", root);

            if (!valid) {
                sessionManager.getPromptProcessing().put(chatRoomId, false); //이 prompt 를 종료시키고 whattodo로 진입
                String failMessage = "통신성향 분석 또는 요금제 추천 중 오류가 발생했습니다. 다시 시도해 주세요. \n 어떤 도움을 드릴까요? {요금제 추천, 요금제 조회, 사용자 정보 알기, 심심풀이} 선택해주세요! ";
                saveChatMessage(userId, currentChatRoom, failMessage, true, false, false,"mock reason");
                return ChatResponseDto.of(failMessage, chatRoomId, userId);
            }

            UserPreferenceDto preference = objectMapper.treeToValue(root, UserPreferenceDto.class);

            log.info("preference : {}", preference);
            recommendationResponseDto = sendToRecommendationModule(preference, userId);

            memory.clear();
            memory.add(SystemMessage.from(preference.toString()+"이러한 성향을 가진 사용자에게 어떤 요금제를 추천해 줬어요. 사용자가 그 요금제를 알려주면, 챗봇이 그 요금제를 추천한 이유가 무엇일지 예상해서 당신이 추천한 것처럼 대답하세요 한문장으로 정리해주세요. 현재 시제로 정리하세요"));
            String reason = chain.execute(recommendationResponseDto.getRecommendPlans().get(0).toString());
            log.info("추천 이유 : " + reason);
            memory.clear();
            memory.add(SystemMessage.from(feedbackPrompt));

            recommendPlans= recommendationResponseDto.getRecommendPlans();
            chatResponseDto = generatePlanRecommendReply(recommendationResponseDto,userId,currentChatRoom,chatRoomId,false, reason);

           return chatResponseDto;
        }
        //feedback 추천
        if(JsonFeedbackParser.parseFeedbackResponse(response) != null){
            log.info("feedback 진입 : " + message);
            Long feedBackCode =  JsonFeedbackParser.parseFeedbackResponse(response).getFeedbackCode();
            log.info("feedback 코드 : " + feedBackCode + "feedback의 감정 : " + sentiment);
            Long sentimentCode = 1L;
            if(sentiment.equals("혐오")||sentiment.equals("놀람") ||sentiment.equals("슬픔")) sentimentCode=2L;
            if(sentiment.equals("분노")) sentimentCode = 3L;
            log.info("감지된 keyword( keyword추천이 아니라면 null) : "+this.extractedKeyword);
            FeedBackDto feedBackDto = FeedBackDto.builder()
                    .keyword(this.extractedKeyword)
                    .sentimentCode(sentimentCode)
                    .detailCode(feedBackCode)
                    .build();
            RecommendationResponseDto recommendationResponseDto2= null;
            if(recommendPlans == null){ // 정보수집 기반 추천의 피드백
                recommendationResponseDto= this.sendFeedBackToRecommendationModule(feedBackDto,userId,recommendationResponseDto.getRecommendPlans().get(0).getPlan().getPlanId());
            }else{//키워드 기반 추천의 피드백
                recommendationResponseDto = this.sendFeedBackToRecommendationModule(feedBackDto,userId,recommendPlans.get(0).getPlan().getPlanId());
            }
            //추천 이유 받는 prompt로 전환
            memory.clear();
            memory.add(SystemMessage.from(message+" 라는 피드백을 가진 사용자에게 다시 어떤 요금제를 추천해 줬어요. 사용자가 그 요금제를 알려주면, 챗봇이 그 요금제를 추천한 이유가 무엇일지 예상해서 (피드백이 반영되었음을 어필) 당신이 추천한 것처럼 대답하세요 한문장으로 정리해주세요. 현재 시제로 정리하세요"));
            String reason = chain.execute(recommendationResponseDto.getRecommendPlans().get(0).toString());
            log.info("추천 이유 : " + reason);
            memory.clear();
            memory.add(SystemMessage.from(feedbackPrompt));
            boolean isFeedback = true;
            chatResponseDto = generatePlanRecommendReply(recommendationResponseDto,userId,currentChatRoom,chatRoomId, isFeedback,reason);

            return chatResponseDto;
        }


        return ChatResponseDto.of(response, chatRoomId, userId);

    }

    private ChatResponseDto showPlansByCategory(String value) {
        List<FilterListResponseDto> filterListResponseDtoList;
        log.info("value : " + value);
        Long categoryId = Long.parseLong(value);
        log.info("categoryId : " + categoryId);
        filterListResponseDtoList = planClient.getPlansByCategoryId(categoryId);
        log.info("filterListResponseDtoList size : " + filterListResponseDtoList.size());
        return ChatResponseDto.builder()
                .message(filterListResponseDtoList.toString())
                .isBot(true)
                .isPlanShow(true)
                .build();
    }

    private ChatResponseDto showPlansByBenefit(String value) {
        List<FilterListResponseDto> filterListResponseDtoList;
        log.info("value : " + value);
        if(value.isEmpty()){
            filterListResponseDtoList = planClient.getAllPlans();
        }else{
            Long benefitId = Long.parseLong(value);
            log.info("benefitId : " + benefitId);
            filterListResponseDtoList = planClient.getPlansByBenefitsId(benefitId);
        }


        log.info("filterListResponseDtoList size : " + filterListResponseDtoList.size());

        return ChatResponseDto.builder()
                .message(filterListResponseDtoList.toString())
                .isBot(true)
                .isPlanShow(true)
                .build();

    }

    public boolean badWordCheck(Long userId, String message, Long sentAt) {
        //금칙어 포함 시 금칙어 사용 기록에 저장 ( admin 모듈 ) 후 처리
        log.info("금칙어 check 중 ... message : " + message, "sentAt : " + sentAt );
        if (Arrays.asList(environment.getActiveProfiles()).contains("test")) {
            return false;
        }
        boolean check = badWordService.checkBadWord(message);
        log.info("금칙어 check 결과 : " + check);
        if (check) {
            saveForbiddenWordRecord(userId, message,sentAt);
            log.info("saveForbiddenWordRecord 기록 완료");
            return true;
        }
        log.info("saveForbiddenWordRecord 기록 완료");
        return false;
    }

    public ChatMessageDto saveChatMessage(Long userId, ChatRoom chatRoom, String message, boolean isBot, boolean isRecommend, boolean isPlanShow, String recommendReason) {
        log.debug("채팅 메시지 저장 준비: 사용자 ID={}, 채팅방 ID={}, 챗봇 여부={}, 메시지='{}'", userId, chatRoom.getChatRoomId(), isBot, message);

        // LocalDateTime -> 유닉스 타임스탬프 (초 단위)
        long unixTimestamp = LocalDateTime.now().atZone(ZoneId.systemDefault()).toEpochSecond();

        ChatMessage chatMessage = ChatMessage.builder()
                .userId(userId)
                .chatRoom(chatRoom)
                .message(message)
                .isBot(isBot)
                .isRecommend(isRecommend)
                .isPlanShow(isPlanShow)
                .recommendReason(recommendReason)
                .build();

        chatMessage.setTimestamp(unixTimestamp);

        ChatMessage chat = chatMessageRepository.save(chatMessage);
        ChatMessageDto chatMessageDto = ChatMessageDto.builder()
                .message(chat.getMessage())
                .timestamp(chat.getTimestamp())
                .messageId(chat.getId())
                .build();

        return chatMessageDto;
    }

    private void saveForbiddenWordRecord(Long userId, String message, Long sentAt) {
        log.info("chatMessageInfo(findTopByOrderByIdDesc) : "+ message);

        badWordService.sendBadwordRecord(userId, sentAt, message);
        log.info("admin모듈에 전송 완료");
    }

    private RecommendationResponseDto sendToRecommendationModule(UserPreferenceDto preference, Long userId) {
        BaseResponseDto<RecommendationResponseDto> recommendBaseResponse = recommendClient.recommend(preference, userId);
        log.info("recommendBaseResponse : {}", recommendBaseResponse);
        return recommendBaseResponse.getData();
    }

    private List<RecommendPlanDto> sendKeywordToRecommendationModule(String keyword) {
        BaseResponseDto<List<RecommendPlanDto>> recommendBaseResponse = recommendClient.recommendByKeyword(keyword);
        log.info("recommendBaseResponse : {}", recommendBaseResponse);
        return recommendBaseResponse.getData();
    }

    private RecommendationResponseDto sendFeedBackToRecommendationModule(FeedBackDto feedBackDto, Long userId, Long planId) {
        try {
            BaseResponseDto<RecommendationResponseDto> recommendBaseResponse = recommendClient.recommendAfterFeedback(feedBackDto, userId, planId);
            log.info("recommendBaseResponse : {}", recommendBaseResponse);
            return recommendBaseResponse.getData();
        } catch (Exception e) {
            log.error("sendFeedBackToRecommendationModule 예외 발생", e);
            throw e; // 예외를 다시 던지거나 null 반환 등 처리
        }
    }
    public List<ChatHistoryResponseDto> getChatHistory(ChatHistoryRequestDto request) {
        log.info(
                "채팅 이력 조회 요청. 채팅방 ID: {}, 사용자 ID: {}, 마지막 메시지 ID: {}, 페이지 크기: {}",
                request.getChatRoomId(), request.getUserId(), request.getLastMessageId(), request.getPageSize()
        );
        Pageable pageable = PageRequest.of(0, request.getPageSize());
        List<ChatMessage> chatMessages;

        if (request.getLastMessageId() != null) {
            log.info("이전 메시지부터 조회. 채팅방 ID: {}, 마지막 메시지 ID: {}", request.getChatRoomId(), request.getLastMessageId());
            chatMessages = chatMessageRepository.findPriorMessages(
                    request.getChatRoomId(),
                    request.getUserId(),
                    request.getLastMessageId(),
                    pageable
            );
        } else {
            log.info("최근 메시지 조회. 채팅방 ID: {}", request.getChatRoomId());
            chatMessages = chatMessageRepository.findRecentMessages(
                    request.getChatRoomId(),
                    request.getUserId(),
                    pageable
            );
        }
        log.info("조회된 메시지 수: {}", chatMessages.size());
        return chatMessages.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ChatMessageDetailResponseDto getChatMessageDetail(Long messageId) {
        log.info("욕설이 포함된 messageId 찾기: " +messageId);
        ChatMessage chatMsg = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new ChatException(ErrorCode.CHAT_MESSAGE_RETRIEVE_FAILED));
        log.info("admin에서 호출한, message를 조회 messageId"+ chatMsg.getId() );

        return new ChatMessageDetailResponseDto(
                chatMsg.getId(),
                chatMsg.getMessage(),
                chatMsg.getTimestamp()
        );
    }

    private ChatResponseDto generatePlanRecommendReply(RecommendationResponseDto recommendationResponse, Long userId, ChatRoom currentChatRoom,Long chatRoomId,boolean isFeedback, String reason){
        String finalReply = "";
        log.info("recommendationResponse : {}", recommendationResponse);
        List<RecommendPlanDto> recommendPlans = recommendationResponse.getRecommendPlans();
        if (recommendPlans == null || recommendPlans.isEmpty()) {
            sessionManager.getPromptProcessing().put(chatRoomId, false); //다시 whattodo로 들어가게끔.
            String failMessage = "분석된 통신 성향에 맞는 요금제를 찾지 못했습니다. 다시 시도해 주세요.";
            saveChatMessage(userId, currentChatRoom, failMessage, true, false, false,"mock reason");
            return ChatResponseDto.of(failMessage, chatRoomId, userId);
        }

        String recommendationsText = recommendPlans.stream()
                .map(recommend -> {
                    PlanDto plan = recommend.getPlan();
                    log.info("plan : {}", plan);
                    return String.format(
                            "요금제: '%s'\n" +
                                    "- 월정액: %d원\n" +
                                    "- 제공량: %d %s\n" +
                                    "- 제공 기간: %s\n" +
                                    "- 테더링 데이터: %d %s\n" +
                                    "- 음성 통화량: %d분\n" +
                                    "- 추가 통화 허용량: %d분\n" +
                                    "- 가족 결합 가능: %s\n" +
                                    "- 카테고리: %s\n",
                            plan.getPlanName(),
                            plan.getMonthlyFee(),
                            plan.getDataAllowance(),
                            plan.getDataAllowanceUnit(),
                            plan.getDataPeriod(),
                            plan.getTetheringDataAmount(),
                            plan.getTetheringDataUnit(),
                            plan.getVoiceCallAmount(),
                            plan.getAdditionalCallAllowance(),
                            plan.isFamilyDataEnabled() ? "가능" : "불가능",
                            plan.getPlanCategory()
                    );
                })
                .collect(Collectors.joining("\n"));
        if(isFeedback){
            finalReply = String.format(
                    "고객님의 통신 성향을 바탕으로 다음 요금제들을 추천해 드립니다.["+reason+ "]\n\n%s 이 요금제에 대해서 평가 해주세요! 가격, 데이터, 부가서비스 등 만족하시나요? 아니라면, 어떤 게 마음에 안드시는 지 알려주세요! 그만두시려면 \"끝\"이라고 해주세요",
                    recommendationsText
            );

        }else{
            finalReply = String.format(
                    "고객님의 통신 성향을 바탕으로 다음 요금제들을 추천해 드립니다.["+reason+"]\n\n%s 이 요금제에 대해서 평가 해주세요! 괜찮은 요금제 같나요?\",",
                    recommendationsText
            );
        }

        ChatMessageDto chatMessageDto = saveChatMessage(userId, currentChatRoom, finalReply, true, true, false,reason);
        return ChatResponseDto.builder()
                .messageId(chatMessageDto.getMessageId())
                .userId(userId)
                .chatRoomId(chatRoomId)
                .message(finalReply)
                .isBot(true)
                .timestamp(LocalDateTime.now())
                .isRecommended(true)
                .recommendationReason(reason)
                .build();
    }
    private ChatHistoryResponseDto convertToDto(ChatMessage chatMessage) {
        log.debug("ChatMessage를 DTO로 변환 중: 메시지 ID={}, 내용='{}'", chatMessage.getId(), chatMessage.getMessage());

        LocalDateTime localDateTime = Instant.ofEpochSecond(chatMessage.getTimestamp())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        log.debug("타임스탬프 변환 성공: {} -> {}", chatMessage.getTimestamp(), localDateTime);
        log.info(
                "message : "  + chatMessage.getMessage() +
                " isplanshow : " + chatMessage.isPlanShow() +
                " isRecommend : " + chatMessage.isRecommend()+
                " recommendreason : " +  chatMessage.getRecommendReason());
        return  ChatHistoryResponseDto.builder()
                    .messageId(chatMessage.getId())
                    .content(chatMessage.getMessage())
                    .senderUserId(chatMessage.getUserId())
                    .chatRoomId(chatMessage.getChatRoom().getChatRoomId())
                    .isBot(chatMessage.isBot())
                    .timestamp( localDateTime)
                    .isPlanShow(chatMessage.isPlanShow())
                    .isRecommended(chatMessage.isRecommend())
                    .recommendReason(chatMessage.getRecommendReason())
                    .build();
    }



    private Optional<String> detectPromptSwitch(String response) {
        return promptMap.keySet().stream()
                .filter(response::contains)
                .findFirst();
    }

    private Optional<String> detectEndSignal(String response) {
        return endSignalMap.keySet().stream()
                .filter(response::contains)
                .findFirst();
    }

    private ChatResponseDto endPromptAndRespond(String context, Long chatRoomId, Long userId,ChatRoom currentChatRoom,ChatMemory memory,String whattodoPrompt) {
        log.info("task가 끝이 났습니다.", context);
        sessionManager.getPromptProcessing().put(chatRoomId, false);
        log.info("sessionManager를 호출하여 task process 상태를 false로 만들었습니다.");
        memory.clear();
        memory.add(SystemMessage.from(whattodoPrompt));
        String message = "어떤 도움을 드릴까요? {요금제 추천, 요금제 조회, 사용자 정보 알기, 심심풀이} 선택해주세요!";
        saveChatMessage(userId, currentChatRoom, message, true, false, false,"mock reason");
        return ChatResponseDto.of(message, chatRoomId, userId);
    }
}