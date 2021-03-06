package com.fota.fotamargin.thread;

import com.alibaba.fastjson.JSON;
import com.fota.asset.domain.UserContractDTO;
import com.fota.asset.domain.enums.UserContractStatus;
import com.fota.asset.service.AssetService;
import com.fota.common.Page;
import com.fota.common.Result;
import com.fota.fotamargin.common.constant.MarginConstant;
import com.fota.fotamargin.common.constant.RedisConstant;
import com.fota.fotamargin.common.entity.InternalMethodResult;
import com.fota.fotamargin.common.entity.LoopOperationResult;
import com.fota.fotamargin.common.enums.*;
import com.fota.fotamargin.common.util.*;
import com.fota.fotamargin.exception.MarginException;
import com.fota.fotamargin.manager.AssetManager;
import com.fota.fotamargin.manager.MarginManager;
import com.fota.fotamargin.manager.RedisManager;
import com.fota.fotamargin.manager.RocketMqManager;
import com.fota.fotamargin.manager.log.ForcedLog;
import com.fota.fotamargin.manager.trade.UserContractLeverManager;
import com.fota.fotamargin.message.EmailAndSmsRunnable;
import com.fota.fotamargin.message.MessageThreadPool;
import com.fota.fotamargin.message.MqRunnable;
import com.fota.margin.domain.ForceNotifyData;
import com.fota.margin.domain.MarginForceOrder;
import com.fota.margin.domain.MarginMqDto;
import com.fota.margin.domain.MqConstant;
import com.fota.trade.domain.*;
import com.fota.trade.domain.enums.OrderCloseType;
import com.fota.trade.domain.enums.OrderTypeEnum;
import com.fota.trade.service.ContractOrderService;
import com.fota.trade.service.UserContractLeverService;
import com.fota.trade.service.UserPositionService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.CountDownLatch;

import static com.fota.fotamargin.common.constant.MarginConstant.SCALE;

/**
 * @author taoyuanming
 * Created on 2018/8/2
 * Description ????????????????????????
 */
public class BatchForcedLiquidationRunner implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(BatchForcedLiquidationRunner.class);

    private static RedisManager redisManager = BeanUtil.getBean(RedisManager.class);
    private static ContractOrderService contractOrderService = BeanUtil.getBean(ContractOrderService.class);
    private static MarginManager marginManager = BeanUtil.getBean(MarginManager.class);
    private static AssetManager assetManager = BeanUtil.getBean(AssetManager.class);
    private static UserContractLeverManager userContractLeverManager = BeanUtil.getBean(UserContractLeverManager.class);
    private static AssetService assetService = BeanUtil.getBean(AssetService.class);

    private static final String PREFIX = RedisConstant.RUNNING_FORECD_LIQUIDATION_USER_;

    private List<Long> shardingUserIdList;
    private CountDownLatch countDownLatch;
    private Map<String, BigDecimal> latestPriceMap;
    private Map<Long, ContractCategoryDTO> contractCategoryDTOMap;
    private Map<Long, Map<Integer, Integer>> userIdAssetIdLeverMapMap = new HashMap<>();
    private UserContractDTO userContractDTO;

    public BatchForcedLiquidationRunner(List<Long> shardingUserIdList, CountDownLatch countDownLatch, Map<String, BigDecimal> latestPriceMap, Map<Long, ContractCategoryDTO> contractCategoryDTOMap) {
        this.shardingUserIdList = shardingUserIdList;
        this.countDownLatch = countDownLatch;
        this.latestPriceMap = latestPriceMap;
        this.contractCategoryDTOMap = contractCategoryDTOMap;
    }

    @Override
    public void run() {
        List<UserContractDTO> userContractDTOList = assetManager.selectContractAccountByUserId(shardingUserIdList);
        if (CollectionUtils.isNotEmpty(userContractDTOList)) {
            for (UserContractDTO userContractDTO : userContractDTOList) {
                try {
                    if (JedisClusterUtil.setIfAbsentPX(PREFIX + userContractDTO.getId(), 60000L)) {
                        long start = System.currentTimeMillis();
                        ForcedLog.forcedLog("forcedLiquidation>> runningForcedLiquidation start: userContractDTO:{} start:{}", JSON.toJSONString(userContractDTO), start);
                        runningForcedLiquidation(userContractDTO, start);
                        redisManager.deleteValue(PREFIX + userContractDTO.getId());
                        long end = System.currentTimeMillis();
                        ForcedLog.forcedLog("forcedLiquidation>> runningForcedLiquidation end: userContractDTO:{} end:{}, start:{}, elapse:{}", JSON.toJSONString(userContractDTO), end, start, end - start);
                    } else {
                        ForcedLog.forcedLog("forcedLiquidation>> runningForcedLiquidation-setIfAbsentPX-false: userContractDTO:{} currentTimeMillis:{}", JSON.toJSONString(userContractDTO), System.currentTimeMillis());
                    }
                } catch (MarginException e) {
                    redisManager.deleteValue(PREFIX + userContractDTO.getId());
                    ForcedLog.forcedLog(LogUtils.format("forcedLiquidation>> runningForcedLiquidation exception! userContractDTO: %s, exception:", JSON.toJSONString(userContractDTO)), e);
                    LOGGER.error(e.getMessage(), e);
                } catch (Exception e1) {
                    redisManager.deleteValue(PREFIX + userContractDTO.getId());
                    ForcedLog.forcedLog(LogUtils.format("forcedLiquidation>> runningForcedLiquidation exception! userContractDTO: %s, exception:", JSON.toJSONString(userContractDTO)), e1);
                    LOGGER.error("BatchForcedLiquidationRunner exception!", e1);
                }
            }
        }

        countDownLatch.countDown();
    }

    /**
     * ????????????????????????
     *
     * @param contractUser
     */
    private void runningForcedLiquidation(UserContractDTO contractUser, long startTime) throws MarginException {
        Long userId = contractUser.getUserId();
        if (userId == null) {
            LOGGER.error("userId is empty, UserContractDTO:{}", contractUser);
            return;
        }
        this.userContractDTO = contractUser;
        ForcedLog.forcedLog("forcedLiquidation>> runningForcedLiquidation userContractDTO:{}", contractUser);

        Boolean allForcedOrderDone = false;
        Boolean taken = false;
        List<UserPositionDTO> userPositionDTOList = null;
        //???????????????
        BigDecimal userTotalRights = null;

        if (UserContractStatus.LIMIT.getCode().equals(contractUser.getStatus())) {
            taken = true;
        }

        //?????????????????????
        Set<Long> deliveringIdSet = marginManager.getDeliveringContractIds();
        if (taken) {
            // ?????????????????????????????????
            allForcedOrderDone = isAllForcedOrderDone(contractUser);
            if (!allForcedOrderDone) {
                ForcedLog.forcedLog("forcedLiquidation>> allForcedOrderDone is false, userContractDTO:{}", contractUser);
                return;
            }
            ForcedLog.forcedLog("forcedLiquidation>> allForcedOrderDone is true, userContractDTO:{}", contractUser);
            //??????????????????????????????????????????
            this.updateContractAccountStatus(userId, UserContractStatus.NORMAL, contractUser.getStatus());

            Boolean notNotify = redisManager.sRem(RedisConstant.FORECD_LIQUIDATION_WARN_USER_RECORD, String.valueOf(userId));
            if (notNotify) {
                ForcedLog.forcedLog("forcedLiquidation>> notNotify is true, userContractDTO:{}", contractUser);
                // ???????????????MQ?????? ?????????????????????
                String notifyData = (String) redisManager.hget(RedisConstant.FORCE_ORDER_INFO, String.valueOf(contractUser.getUserId()));
                if (notifyData != null) {
                    ForceNotifyData forceNotifyData = new ForceNotifyData(System.currentTimeMillis(), JSON.parseArray(notifyData, MarginForceOrder.class));
                    MessageThreadPool.execute(new MqRunnable(MqConstant.MARGIN_TOPIC, MqConstant.FORCE_WARN, new MarginMqDto(MqConstant.TYPE_FORCE, userId, TimeUtils.getTimeInMillis(), FUUID.getUUUID(), forceNotifyData)));
                    MessageThreadPool.execute(new EmailAndSmsRunnable(userId, MqConstant.TYPE_FORCE, forceNotifyData));
                    redisManager.hdel(RedisConstant.FORCE_ORDER_INFO, String.valueOf(contractUser.getUserId()));
                } else {
                    ForcedLog.forcedLog("forcedLiquidation>>forceOrderInfo is empty, userContractDTO:{}", contractUser);
                    LOGGER.info("forcedLiquidation>>forceOrderInfo is empty, userContractDTO:{}", contractUser);
                }
            }

            //?????????????????????????????????????????????
            //????????????????????????????????????????????????
            InternalMethodResult internalMethodResult = putUserIdAssetIdLeverMapMap(userId);
            if (InternalMethodResultCodeEnum.ERROR.getCode().equals(internalMethodResult.getCode())) {
                ForcedLog.forcedLog("forcedLiquidation>> putUserIdAssetIdLeverMapMap error, userContractDTO:{}", contractUser);
                return;
            }
            //????????????????????????
            userPositionDTOList = marginManager.listPositionByUserId(userId, deliveringIdSet);
            ForcedLog.forcedLog("forcedLiquidation>>  userPositionDTOList:{}, userContractDTO:{}", userPositionDTOList, contractUser);
            BigDecimal positionProfit = calculatePositionProfit(userPositionDTOList);
            userTotalRights = new BigDecimal(contractUser.getAmount()).add(positionProfit);
            ForcedLog.forcedLog("forcedLiquidation>>  amount:{}, positionProfit:{}, userTotalRights:{}, userContractDTO", contractUser.getAmount(), positionProfit, userTotalRights, contractUser);
            if (userTotalRights.compareTo(BigDecimal.ZERO) < 0) {
                if (notNotify) {
                    //????????????????????????
                    // ???????????????MQ  ????????????????????????
                    MessageThreadPool.execute(new MqRunnable(MqConstant.MARGIN_TOPIC, MqConstant.PENETRATE_WARN, new MarginMqDto(MqConstant.TYPE_PENETRATE, userId, null, userTotalRights, TimeUtils.getTimeInMillis(), FUUID.getUUUID())));
                    MessageThreadPool.execute(new MqRunnable(MqConstant.MARGIN_TOPIC, MqConstant.PENETRATE_RECORD, new MarginMqDto(MqConstant.TYPE_PENETRATE, userId, null, userTotalRights, TimeUtils.getTimeInMillis(), FUUID.getUUUID())));
                    MessageThreadPool.execute(new EmailAndSmsRunnable(userId, MqConstant.TYPE_PENETRATE));
                    marginManager.updateOrInsertUserOperationLimit(userId);
                }
            }

            //?????????????????????????????????????????????????????????????????????
            //?????????????????? ???????????????marginManager.listPositionByUserId(userId, deliveringIdSet)
            //???????????????????????????????????????????????????
//            if (CollectionUtils.isEmpty(userPositionDTOList)) {
//                return;
//            }
        }

        if (!userIdAssetIdLeverMapMap.containsKey(userId)) {
            //????????????????????????????????????????????????
            InternalMethodResult internalMethodResult = putUserIdAssetIdLeverMapMap(userId);
            if (InternalMethodResultCodeEnum.ERROR.getCode().equals(internalMethodResult.getCode())) {
                ForcedLog.forcedLog("forcedLiquidation>> putUserIdAssetIdLeverMapMap error, userContractDTO:{}", contractUser);
                return;
            }
        }

        if (CollectionUtils.isEmpty(userPositionDTOList)) {
            //????????????????????????
            userPositionDTOList = marginManager.listPositionByUserId(userId, deliveringIdSet);
            ForcedLog.forcedLog("forcedLiquidation>>  userPositionDTOList:{}, userContractDTO:{}", userPositionDTOList, contractUser);
//            if (CollectionUtils.isEmpty(userPositionDTOList)) {
////                LOGGER.warn("userPositionDTOList is empty! userContractDTO:{}", contractUser);
//                if (userTotalRights == null) {
//                    BigDecimal positionProfit = calculatePositionProfit(userPositionDTOList);
//                    userTotalRights = new BigDecimal(contractUser.getAmount()).add(positionProfit);
//                    ForcedLog.forcedLog("forcedLiquidation>>  amount:{}, positionProfit:{}, userTotalRights:{}, userContractDTO:{}", contractUser.getAmount(), positionProfit, userTotalRights, contractUser);
//                }
//                //??????????????????????????????????????????
//                if (userTotalRights.compareTo(BigDecimal.ZERO) > 0) {
//                    marginManager.marginUnfreezeLimitOperation(userId);
//                }
//                //???????????????????????????????????????????????????
//                return;
//            }
        }

        if (userTotalRights == null) {
            BigDecimal positionProfit = calculatePositionProfit(userPositionDTOList);
            userTotalRights = new BigDecimal(contractUser.getAmount()).add(positionProfit);
            ForcedLog.forcedLog("forcedLiquidation>>  amount:{}, positionProfit:{}, userTotalRights:{}, userContractDTO:{}", contractUser.getAmount(), positionProfit, userTotalRights, contractUser);
        }

        //??????????????????????????????????????????
        if (userTotalRights.compareTo(BigDecimal.ZERO) > 0) {
            marginManager.marginUnfreezeLimitOperation(userId);
        }

        // ??????????????? = user_contract.amount + ???????????? = ???????????? + ???????????????  + ??????(???????????????)
        // (????????????UserContractDTO???amount??????) = user_contract.amount = ???????????? + ???????????????  + ??????(???????????????) - ???????????????
        //?????????????????????0??? ??????????????? = (????????????UserContractDTO???amount??????) + ??????????????? + ???????????????
        //???????????????????????????????????????
//        BigDecimal totalMargin = userTotalRights.max(new BigDecimal(0));

        BigDecimal totalMargin = userTotalRights;

        //???????????????
//        BigDecimal totalPositionValue = calculatePositionValue(userPositionDTOList);
//        if (totalPositionValue.compareTo(BigDecimal.ZERO) == 0) {
//            //???????????????0
//            //???????????????????????????????????????????????????
//            return;
//        }
        //??????????????????
//        BigDecimal mc = totalMargin.divide(totalPositionValue,SCALE, RoundingMode.HALF_UP);
//        ForcedLog.forcedLog("forcedLiquidation>>  mc:{}, totalPositionValue:{}, userContractDTO:{}", mc, totalPositionValue, contractUser);

        //??????mc???0.1??????
//        if (mc.compareTo(MarginConstant.THRESHOLD_MC) > 0) {
//            ForcedLog.forcedLog("forcedLiquidation>>  not trigger threshold mc, userContractDTO:{}", contractUser);
//            return;
//        }

        // ????????????????????????????????????????????????(??????????????????)
        BaseQuery baseQuery = new BaseQuery();
        baseQuery.setUserId(userId);
        List<Integer> orderStatus = new ArrayList<>();
        orderStatus.add(OrderStatusEnum.COMMIT.getCode());
        orderStatus.add(OrderStatusEnum.PART_MATCH.getCode());
        baseQuery.setOrderStatus(orderStatus);
        // TODO: 2018/8/29  ???????????????
        baseQuery.setOrderType(OrderTypeEnum.LIMIT.getCode());
        List<ContractOrderDTO> contractOrderDTOList = marginManager.getAllContractOrder(baseQuery, deliveringIdSet);
        ForcedLog.forcedLog("forcedLiquidation>>  contractOrderDTOList:{}, userContractDTO:{}", contractOrderDTOList, contractUser);
        //???????????????
//        if (CollectionUtils.isEmpty(contractOrderDTOList)) {
////            LOGGER.warn("contractOrderDTOList is empty! userContractDTO:{}", contractUser);
////            return;
//        }

        Map<Long, Map<Integer, List<ContractOrderDTO>>> orderListMapMap = classifyOrder(contractOrderDTOList);
        Map<Long, Map<Integer, UserPositionDTO>> positionMapMap = classifyPosition(userPositionDTOList);

        LoopOperationResult loopOperationResult = loopOperation(contractUser, allForcedOrderDone, totalMargin, userIdAssetIdLeverMapMap.get(userId), orderListMapMap, positionMapMap);
        if (LoopOperationResultCodeEnum.RETURN.getCode().equals(loopOperationResult.getCode())) {
            return;
        }
//        BigDecimal t1 = loopOperationResult.getT1();

        // ??????????????????
        this.updateContractAccountStatus(userId, UserContractStatus.LIMIT, contractUser.getStatus());

        if (MapUtils.isNotEmpty(orderListMapMap)) {
            //????????????????????????????????????????????????
            List<Integer> orderTypeList = new ArrayList<>();
            orderTypeList.add(OrderTypeEnum.LIMIT.getCode());
            orderTypeList.add(OrderTypeEnum.MARKET.getCode());
            //?????????????????????
            contractOrderService.cancelOrderByOrderType(userId, orderTypeList, new HashMap<>());

            orderListMapMap = new HashMap<>();
            loopOperationResult = loopOperation(contractUser, allForcedOrderDone, totalMargin, userIdAssetIdLeverMapMap.get(userId), orderListMapMap, positionMapMap);
            if (LoopOperationResultCodeEnum.RETURN.getCode().equals(loopOperationResult.getCode())) {
                return;
            }
//            t1 = loopOperationResult.getT1();
        }

        List<Long> exceptionId = new ArrayList<>();
        //??????????????????
        userPositionDTOList.sort(new Comparator<UserPositionDTO>() {
            @Override
            public int compare(UserPositionDTO o1, UserPositionDTO o2) {
                BigDecimal o1Profit = calculatePositionProfitWithoutThrow(o1, exceptionId);
                BigDecimal o2Profit = calculatePositionProfitWithoutThrow(o2, exceptionId);
                if (o1Profit.compareTo(o2Profit) < 0) {
                    return -1;
                } else if (o1Profit.compareTo(o2Profit) > 0) {
                    return 1;
                } else {
                    return 0;
                }
            }
        });
        if (CollectionUtils.isNotEmpty(exceptionId)) {
            ForcedLog.forcedLog("forcedLiquidation>>  userPositionDTOList sort exception! userContractDTO:{}", contractUser);
            throw new MarginException("userPositionDTOList.sort exception!");
        }

//        BigDecimal tempTotalPositionValue = totalPositionValue;
//        List<BigDecimal> positionValueList = new ArrayList<>();
//        for (UserPositionDTO userPositionDTO : userPositionDTOList) {
//            positionValueList.add(calculateSinglePositionValue(userPositionDTO));
//        }
        Map<UserPositionDTO, BigDecimal> targetPriceMap = this.calculateTargetPrice(contractUser, userPositionDTOList);
        List<BigDecimal> positionProfitList = new ArrayList<>();
        for (UserPositionDTO userPositionDTO : userPositionDTOList) {
            positionProfitList.add(this.calculateChangedProfit(userPositionDTO, targetPriceMap.get(userPositionDTO)));
        }
        //userTotalRights
        BigDecimal tempTotalMargin = totalMargin;
//        BigDecimal tempMc;
//        int j = positionValueList.size();
        int j = positionProfitList.size();
        int index = 0;
        UserPositionDTO tempUserPositionDTO;
        BigDecimal mr = this.calculatePositionMargin(userPositionDTOList);
        //????????????????????????
        for (int i = 0; i < j; i++) {
            //???????????????????????????????????? ??????????????? = ???????????????  - ???????????????
            tempUserPositionDTO = userPositionDTOList.get(i);
//            tempTotalMargin = tempTotalMargin.subtract(calculatePositionMargin(tempUserPositionDTO));
//            tempTotalPositionValue = tempTotalPositionValue.subtract(positionValueList.get(i));
            tempTotalMargin = tempTotalMargin.add(positionProfitList.get(i));
            mr = mr.subtract(this.calculatePositionMargin(tempUserPositionDTO));


            //???????????????????????????????????????????????????=0?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????mc > t1 ???
            //???????????????????????????  ??????????????????0 ????????????????????????????????????
//            if (i == j - 1) {
//                if (tempTotalMargin.compareTo(BigDecimal.ZERO) <= 0) {
//                    //index = j ????????????
//                    index = j;
//                    break;
//                } else {
//                    index = i;
//                    break;
//                }
//            }

//            tempMc = tempTotalMargin.divide(tempTotalPositionValue, SCALE, RoundingMode.HALF_UP);
//            if (tempMc.compareTo(t1) > 0) {
//                index = i;
//                break;
//            }
            if (mr.multiply(MarginConstant.T1).compareTo(tempTotalMargin) < 0) {
                index = i;
                break;
            }
            if (i == j - 1) {
                //index = j ????????????
                index = j;
                break;
            }
        }
        List<MarginForceOrder> marginForceOrderList;
        if (index == j) {
            //index = j ??????????????????
            //???????????????????????????????????????????????????????????????????????????
            //????????????
            marginForceOrderList = batchForcedOrder(userPositionDTOList, index, "", targetPriceMap);
        } else {
//            //???????????????????????????????????????????????????
//            UserPositionDTO indexDto = userPositionDTOList.get(index);
//            UserPositionDTO userPositionDTO = new UserPositionDTO();
//            BeanUtils.copyProperties(indexDto, userPositionDTO);
//            userPositionDTO.setAmount(new BigDecimal(1));
//
//            // TODO: 2018/8/29 ?????????????????????????????????
//            tempTotalMargin = tempTotalMargin.add(calculatePositionMargin(indexDto));
//            tempTotalPositionValue = tempTotalPositionValue.add(positionValueList.get(index));
//            tempMc = tempTotalMargin.divide(tempTotalPositionValue, SCALE, RoundingMode.HALF_UP);
//
//            // TODO: 2018/8/29 ?????????????????????????????????
//            tempTotalMargin = tempTotalMargin.subtract(calculatePositionMargin(userPositionDTO));
//            tempTotalPositionValue = tempTotalPositionValue.subtract(calculateSinglePositionValue(userPositionDTO));
//            if (tempTotalPositionValue.compareTo(BigDecimal.ZERO) == 0) {
//                //??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
//                index = j;
//                forcedUserPositionDTOs = batchForcedOrder(userPositionDTOList, index, "");
//            } else {
//                //??????????????????????????????N-1??????????????????????????????????????????N??????????????????1????????????????????????MC
//                BigDecimal tempMcSubtractOne = tempTotalMargin.divide(tempTotalPositionValue, SCALE, RoundingMode.HALF_UP);
//
//                BigDecimal count = t1.subtract(tempMc).divide(tempMcSubtractOne.subtract(tempMc), SCALE, RoundingMode.HALF_UP);
//                count = count.setScale(0, BigDecimal.ROUND_UP);
//                forcedUserPositionDTOs = batchForcedOrder(userPositionDTOList, index, count.toString());
//            }

            UserPositionDTO indexPositionDTO = userPositionDTOList.get(index);
            tempTotalMargin = tempTotalMargin.subtract(positionProfitList.get(index));
            mr = mr.add(this.calculatePositionMargin(indexPositionDTO));

            BigDecimal differencePrice = this.calculateDifferencePrice(indexPositionDTO, targetPriceMap.get(indexPositionDTO));
            int scale = this.getScale(indexPositionDTO);
            BigDecimal count = mr.multiply(MarginConstant.T1).subtract(tempTotalMargin).divide(this.getLatestPrice(indexPositionDTO).multiply(MarginConstant.T1).divide(this.getCustomLeverage(indexPositionDTO), SCALE, RoundingMode.HALF_UP).add(differencePrice), scale, RoundingMode.UP);
            marginForceOrderList = batchForcedOrder(userPositionDTOList, index, count.toString(), targetPriceMap);
        }
        redisManager.hset(RedisConstant.FORCE_ORDER_INFO, String.valueOf(contractUser.getUserId()), JSON.toJSONString(marginForceOrderList));
        LOGGER.info("forcedLiquidation>>force>>time:{}, elapse:{}, user:{}, indexPrice:{}, userPositionDTOList:{}, assetIdLeverMap:{}", System.currentTimeMillis(), System.currentTimeMillis() - startTime, JSON.toJSONString(contractUser), JSON.toJSONString(this.getIndexPrice(userPositionDTOList)), JSON.toJSONString(userPositionDTOList), userIdAssetIdLeverMapMap.get(contractUser.getUserId()));
        ForcedLog.forcedLog("forcedLiquidation>> marginForceOrderList:{}, userContractDTO", marginForceOrderList, contractUser);
        //?????????????????????????????????MQ
        MessageThreadPool.execute(new MqRunnable(MqConstant.MARGIN_TOPIC, MqConstant.FORCE_RECORD, new MarginMqDto(MqConstant.TYPE_FORCE, userId, TimeUtils.getTimeInMillis(), FUUID.getUUUID())));
        redisManager.sAdd(RedisConstant.FORECD_LIQUIDATION_WARN_USER_RECORD, String.valueOf(userId));

    }

    public BigDecimal calculateChangedProfit(UserPositionDTO userPositionDTO, BigDecimal targetPrice) throws MarginException {
        BigDecimal changedProfit = userPositionDTO.getAmount().abs().multiply(targetPrice.subtract(this.getLatestPrice(userPositionDTO)));
        if (PositionTypeEnum.OVER.getCode().equals(userPositionDTO.getPositionType())) {
            return changedProfit;
        } else {
            return changedProfit.negate();
        }
    }

    public BigDecimal calculateDifferencePrice(UserPositionDTO userPositionDTO, BigDecimal targetPrice) throws MarginException {
        if (PositionTypeEnum.OVER.getCode().equals(userPositionDTO.getPositionType())) {
            return targetPrice.subtract(this.getLatestPrice(userPositionDTO));
        } else {
            return this.getLatestPrice(userPositionDTO).subtract(targetPrice);
        }
    }

    public int getScale(UserPositionDTO userPositionDTO) {
        String assetName = this.contractCategoryDTOMap.get(userPositionDTO.getContractId()).getAssetName();
        if (CoinSymbolEnum.BTC.getName().equalsIgnoreCase(assetName)) {
            return 6;
        } else if (CoinSymbolEnum.ETH.getName().equalsIgnoreCase(assetName)) {
            return 5;
        } else {
            LOGGER.error("getScale fail! assetName unknown, userPositionDTO:{}, assetName:{}", userPositionDTO, assetName);
            return 6;
        }
    }

    public Map<UserPositionDTO, BigDecimal> calculateTargetPrice(UserContractDTO userContractDTO, List<UserPositionDTO> userPositionDTOList) throws MarginException {
        Map<UserPositionDTO, BigDecimal> targetPriceMap = new HashMap<>();
        BigDecimal totalPositionMargin = this.calculatePositionMargin(userPositionDTOList);
        BigDecimal totalRights = new BigDecimal(userContractDTO.getAmount()).add(this.calculatePositionProfit(userPositionDTOList));

        BigDecimal latestPrice;
        BigDecimal occupiedMargin;
        BigDecimal targetPrice;
        BigDecimal requiredMargin;
        for (UserPositionDTO userPositionDTO : userPositionDTOList) {
            latestPrice = this.getLatestPrice(userPositionDTO);
            requiredMargin = this.calculatePositionMargin(userPositionDTO);
            occupiedMargin = requiredMargin.divide(totalPositionMargin, SCALE, RoundingMode.HALF_UP).multiply(totalRights);
            if (PositionTypeEnum.OVER.getCode().equals(userPositionDTO.getPositionType())) {
                targetPrice = MarginConstant.LONG_MULTIPLE.multiply(latestPrice).max(latestPrice.subtract(occupiedMargin.divide(userPositionDTO.getAmount(), SCALE, RoundingMode.HALF_UP)));
            } else {
                targetPrice = MarginConstant.SHORT_MULTIPLE.multiply(latestPrice).min(latestPrice.add(occupiedMargin.divide(userPositionDTO.getAmount(), SCALE, RoundingMode.HALF_UP)));
            }
            targetPriceMap.put(userPositionDTO, targetPrice.setScale(SCALE, RoundingMode.HALF_UP));
            //calculate the totalPositionMargin and the totalRights again after force the userPositionDTO
            totalPositionMargin = totalPositionMargin.subtract(requiredMargin);
            totalRights = totalRights.add(this.calculateChangedProfit(userPositionDTO, targetPrice));
        }
        return targetPriceMap;
    }

    /**
     * ????????????????????????????????????????????????
     * @param userId
     */
    public InternalMethodResult putUserIdAssetIdLeverMapMap(Long userId) throws MarginException {
        //????????????????????????????????????????????????
        List<UserContractLeverDTO> userContractLeverDTOList = null;
        userContractLeverDTOList = userContractLeverManager.listUserContractLever(userId);

        if (CollectionUtils.isEmpty(userContractLeverDTOList)) {
            LOGGER.error("userContractLeverService.listUserContractLever return empty! userId:{}", userId);
            return InternalMethodResult.error();
        }
        Map<Integer, Integer> assetIdLeverMap = new HashMap<>();
        for (UserContractLeverDTO userContractLeverDTO : userContractLeverDTOList) {
            assetIdLeverMap.put(userContractLeverDTO.getAssetId(), userContractLeverDTO.getLever());
        }
        userIdAssetIdLeverMapMap.put(userId, assetIdLeverMap);
        return InternalMethodResult.success();
    }

    public LoopOperationResult loopOperation(UserContractDTO userContractDTO, Boolean allForcedOrderDone, BigDecimal totalMargin, Map<Integer, Integer> assetIdLeverMap, Map<Long, Map<Integer, List<ContractOrderDTO>>> orderListMapMap, Map<Long, Map<Integer, UserPositionDTO>> positionMapMap) throws MarginException {
        Integer code = LoopOperationResultCodeEnum.CONTINUE.getCode();

        Set<Long> contractIdSet = new HashSet<>();
        contractIdSet.addAll(orderListMapMap.keySet());
        contractIdSet.addAll(positionMapMap.keySet());
        if (contractIdSet.size() == 0) {
            return new LoopOperationResult(MarginConstant.T1, LoopOperationResultCodeEnum.RETURN.getCode());
        }

        //??????????????????
        Map<Integer, UserPositionDTO> coPoMap = null;
        //???????????????
        UserPositionDTO coLongPo = null;
        //???????????????
        UserPositionDTO coShortPo = null;
        //?????????????????????????????????
        BigDecimal coLongPoMargin = new BigDecimal(0);
        //?????????????????????????????????
        BigDecimal coShortPoMargin = new BigDecimal(0);


        //??????????????????
        Map<Integer, List<ContractOrderDTO>> coOrListMap = null;
        //???????????????
        List<ContractOrderDTO> coLongOrList = null;
        //???????????????
        List<ContractOrderDTO> coShortOrList = null;
        //?????????????????????????????????
        BigDecimal coLongOrMargin = new BigDecimal(0);
        //?????????????????????????????????
        BigDecimal coShortOrMargin = new BigDecimal(0);
        //???????????????????????? = ????????????????????????????????????????????? = MR
        BigDecimal totalOrPoMargin = new BigDecimal(0);
        BigDecimal customLeverage;
        Map<MarginOrderTypeEnum, List<ContractOrderDTO>> marginOrderTypeEnumListMap;
        BigDecimal positionAddOrderExtraMargin;
        for (Long contractId : contractIdSet) {
            //???????????????????????????????????????
            try {
                customLeverage = new BigDecimal(assetIdLeverMap.get(this.contractCategoryDTOMap.get(contractId).getAssetId()));
            } catch (Exception e) {
                if (this.contractCategoryDTOMap.get(contractId) == null) {
                    throw new MarginException(LogUtils.format("return null get %s from contractCategoryDTOMap", String.valueOf(contractId)));
                }
                if (assetIdLeverMap.get(this.contractCategoryDTOMap.get(contractId).getAssetId()) == null) {
                    throw new MarginException(LogUtils.format("return null get %s from assetIdLeverMap", String.valueOf(this.contractCategoryDTOMap.get(contractId).getAssetId())));
                }
                throw new MarginException("get customLeverage exception");
            }
            coPoMap = positionMapMap.get(contractId);
            if (MapUtils.isNotEmpty(coPoMap)) {
                coLongPo = coPoMap.get(PositionTypeEnum.OVER.getCode());
                coShortPo = coPoMap.get(PositionTypeEnum.EMPTY.getCode());
                coLongPoMargin = calculatePositionMargin(coLongPo);
                coShortPoMargin = calculatePositionMargin(coShortPo);
            }

            coOrListMap = orderListMapMap.get(contractId);
            if (MapUtils.isNotEmpty(coOrListMap)) {
                coLongOrList = coOrListMap.get(OrderDirectionEnum.BID.getCode());
                coShortOrList = coOrListMap.get(OrderDirectionEnum.ASK.getCode());

                //????????????
                if (CollectionUtils.isNotEmpty(coLongOrList) && coShortPo != null) {
                    marginOrderTypeEnumListMap = filterOrder(coLongOrList, coShortPo);
                    coLongOrList = marginOrderTypeEnumListMap.get(MarginOrderTypeEnum.MARGIN);
                    //???????????????????????????????????????
                    coLongOrMargin = calculateOrderMargin(coLongOrList, customLeverage).add(calculateOrderFee(marginOrderTypeEnumListMap.get(MarginOrderTypeEnum.FEE), customLeverage));
                } else {
                    coLongOrMargin = calculateOrderMargin(coLongOrList, customLeverage);
                }

                //????????????
                if (CollectionUtils.isNotEmpty(coShortOrList) && coLongPo != null) {
                    marginOrderTypeEnumListMap = filterOrder(coShortOrList, coLongPo);
                    coShortOrList = marginOrderTypeEnumListMap.get(MarginOrderTypeEnum.MARGIN);
                    //???????????????????????????????????????
                    coShortOrMargin = calculateOrderMargin(coShortOrList, customLeverage).add(calculateOrderFee(marginOrderTypeEnumListMap.get(MarginOrderTypeEnum.FEE), customLeverage));;
                } else {
                    coShortOrMargin = calculateOrderMargin(coShortOrList, customLeverage);
                }
            }
            if (coShortPoMargin.compareTo(BigDecimal.ZERO) > 0) {
                positionAddOrderExtraMargin = coLongOrMargin.subtract(coShortPoMargin).max(BigDecimal.ZERO).max(coShortOrMargin).add(coShortPoMargin);
            } else {
                positionAddOrderExtraMargin = coShortOrMargin.subtract(coLongPoMargin).max(BigDecimal.ZERO).max(coLongOrMargin).add(coLongPoMargin);
            }


            totalOrPoMargin = totalOrPoMargin.add(positionAddOrderExtraMargin);
        }

//        //??????????????????????????????????????????MMR???
//        BigDecimal mmr = totalOrPoMargin.divide(totalPositionValue, SCALE, RoundingMode.HALF_UP);
//        //????????????????????????
//        BigDecimal timeMaxLeverage = new BigDecimal(1).divide(mmr, SCALE, RoundingMode.HALF_UP);
//        //???????????????????????????
//        BigDecimal halfLeverage = new BigDecimal(MarginConstant.MAX_LEVERAGE / 2);

//        BigDecimal t1 = new BigDecimal(" 0.8");
//        BigDecimal t2 = new BigDecimal("0.6");
//        //  T1 = MMR *  0.6 * L???/(Lmax/2) if L??? <= Lmax/2  Or = MMR * (0.6 + 0.2 * (L???- Lmax/2)/(Lmax/2))	 if L??? > Lmax/2
//        if (timeMaxLeverage.compareTo(halfLeverage) <= 0) {
//            t1 = mmr.multiply(new BigDecimal(0.6)).multiply(timeMaxLeverage).divide(halfLeverage, SCALE, RoundingMode.HALF_UP);
//        } else {
//            t1 = mmr.multiply(new BigDecimal(0.6).add(new BigDecimal(0.2).multiply((timeMaxLeverage.subtract(halfLeverage)).divide(halfLeverage, SCALE, RoundingMode.HALF_UP))));
//        }
//        t2 = t1.multiply(new BigDecimal(0.8));
//        ForcedLog.forcedLog("forcedLiquidation>>  loopOperation, t1:{}, t2:{}, mc:{}, userContractDTO:{}, orderListMapMap:{}", t1, t2, mc, userContractDTO, orderListMapMap);

        if (totalOrPoMargin.multiply(MarginConstant.T1).compareTo(totalMargin) < 0) {
            // ????????????????????????
            if (!allForcedOrderDone) {
                this.updateContractAccountStatus(userContractDTO.getUserId(), UserContractStatus.NORMAL, userContractDTO.getStatus());
            }
            return new LoopOperationResult(MarginConstant.T1, LoopOperationResultCodeEnum.RETURN.getCode());
        }

        if (totalOrPoMargin.multiply(MarginConstant.T2).compareTo(totalMargin) < 0) {
            if (allForcedOrderDone) {
                //  ???????????????????????????
                MessageThreadPool.execute(new MqRunnable(MqConstant.MARGIN_TOPIC, MqConstant.MARGIN_WARN, new MarginMqDto(MqConstant.TYPE_MARGIN, userContractDTO.getUserId(), TimeUtils.getTimeInMillis(), FUUID.getUUUID())));
                MessageThreadPool.execute(new EmailAndSmsRunnable(userContractDTO.getUserId(), MqConstant.TYPE_MARGIN));
                JedisClusterUtil.setIfAbsentEX(RedisConstant.MARGIN_WARNING + userContractDTO.getId(), RedisConstant.MARGIN_WARNING_EXPIRE_TIME);
            } else {
                if (JedisClusterUtil.setIfAbsentEX(RedisConstant.MARGIN_WARNING + userContractDTO.getId(), RedisConstant.MARGIN_WARNING_EXPIRE_TIME)) {
                    //???????????????????????????
                    MessageThreadPool.execute(new EmailAndSmsRunnable(userContractDTO.getUserId(), MqConstant.TYPE_MARGIN));
                    MessageThreadPool.execute(new MqRunnable(MqConstant.MARGIN_TOPIC, MqConstant.MARGIN_WARN, new MarginMqDto(MqConstant.TYPE_MARGIN, userContractDTO.getUserId(), TimeUtils.getTimeInMillis(), FUUID.getUUUID())));
                }
                // ????????????????????????
                this.updateContractAccountStatus(userContractDTO.getUserId(), UserContractStatus.NORMAL, userContractDTO.getStatus());
            }
            LOGGER.info("forcedLiquidation>>margin>>time:{}, totalRights:{}, totalOrPoMargin:{}, userContractDTO:{}, positionMapMap:{}, orderListMapMap:{}, indexPrice:{}", System.currentTimeMillis(), totalMargin, totalOrPoMargin, JSON.toJSONString(userContractDTO), JSON.toJSONString(positionMapMap), JSON.toJSONString(orderListMapMap), JSON.toJSONString(this.getIndexPrice(positionMapMap)));
            return new LoopOperationResult(MarginConstant.T1, LoopOperationResultCodeEnum.RETURN.getCode());
        }

        return new LoopOperationResult(MarginConstant.T1, LoopOperationResultCodeEnum.CONTINUE.getCode());
    }

    /**
     * ?????????????????????????????????
     *
     * @param userId
     * @param userContractStatus
     * @param oldStatus
     */
    public void updateContractAccountStatus(Long userId, UserContractStatus userContractStatus, Integer oldStatus) throws MarginException {
        if (!userContractStatus.getCode().equals(oldStatus)) {
            boolean b = assetService.updateContractAccountStatus(userId, userContractStatus);
            if (!b) {
                throw new MarginException(LogUtils.format("assetService.updateContractAccountStatus fail, userId: %s, newStatus: %s", String.valueOf(userId), String.valueOf(userContractStatus.getCode())));
            }

        }
    }

    /**
     * ????????????
     *
     * @param userPositionDTOList ??????????????????????????????
     * @param index
     * @param count
     */
    public List<MarginForceOrder> batchForcedOrder(List<UserPositionDTO> userPositionDTOList, int index, String count, Map<UserPositionDTO, BigDecimal> targetPriceMap) throws MarginException {
        //?????????????????????
        List<MarginForceOrder> marginForceOrderList = new ArrayList<>();
        MarginForceOrder marginForceOrder;
        //????????????????????????
        if (index == userPositionDTOList.size()) {
            for (UserPositionDTO dto : userPositionDTOList) {
                marginForceOrder = singleForcedOrder(dto, targetPriceMap.get(dto));
                marginForceOrderList.add(marginForceOrder);
            }
            return marginForceOrderList;
        }

        UserPositionDTO userPositionDTO;
        for (int i = 0; i < index; i++) {
            userPositionDTO = userPositionDTOList.get(i);
            marginForceOrder = singleForcedOrder(userPositionDTO, targetPriceMap.get(userPositionDTO));
            marginForceOrderList.add(marginForceOrder);
        }

        userPositionDTO = new UserPositionDTO();
        UserPositionDTO source = userPositionDTOList.get(index);
        BeanUtils.copyProperties(source, userPositionDTO);
        userPositionDTO.setAmount(new BigDecimal(count));
        marginForceOrder = singleForcedOrder(userPositionDTO, targetPriceMap.get(source));
        marginForceOrderList.add(marginForceOrder);
        return marginForceOrderList;
    }

    /**
     * ????????????
     *
     * @param dto
     */
    public MarginForceOrder singleForcedOrder(UserPositionDTO dto, BigDecimal price) {
        ContractOrderDTO contractOrderDTO = new ContractOrderDTO();
        contractOrderDTO.setUserId(dto.getUserId());
        contractOrderDTO.setContractId(dto.getContractId());
        contractOrderDTO.setContractName(dto.getContractName());
        //??????????????? ???????????????
        contractOrderDTO.setOrderDirection(PositionTypeEnum.OVER.getCode().equals(dto.getPositionType()) ? OrderDirectionEnum.ASK.getCode() : OrderDirectionEnum.BID.getCode());
        contractOrderDTO.setOrderType(OrderTypeEnum.ENFORCE.getCode());
        contractOrderDTO.setTotalAmount(dto.getAmount());
        contractOrderDTO.setUnfilledAmount(dto.getAmount());
        contractOrderDTO.setCloseType(OrderCloseType.ENFORCE.getCode());
        contractOrderDTO.setPrice(price);
        Result<Long> result = contractOrderService.orderWithEnforce(contractOrderDTO, new HashMap<>());
        LOGGER.info("forcedLiquidation>> singleForcedOrder: thread:{}, contractOrderDTO:{}, currentTimeMillis:{}, userContractDTO:{}, orderId:{}", JSON.toJSONString(Thread.currentThread()), JSON.toJSONString(contractOrderDTO), System.currentTimeMillis(), JSON.toJSONString(this.userContractDTO), result.getData());
        ForcedLog.forcedLog("forcedLiquidation>> singleForcedOrder: thread:{}, contractOrderDTO:{}, currentTimeMillis:{}", JSON.toJSONString(Thread.currentThread()), JSON.toJSONString(contractOrderDTO), System.currentTimeMillis());
        if (!result.isSuccess()) {
            ForcedLog.forcedLog("forcedLiquidation>> contractOrderService.order fail, code:{}, message:{}, contractOrderDTO:{}", result.getCode(), result.getMessage(), contractOrderDTO);
            LOGGER.error("contractOrderService.order fail, code:{}, message:{}, contractOrderDTO:{}, userContractDTO:{}", result.getCode(), result.getMessage(), contractOrderDTO, JSON.toJSONString(this.userContractDTO));
            LOGGER.info("contractOrderService.order fail, code:{}, message:{}, contractOrderDTO:{}, userContractDTO:{}", result.getCode(), result.getMessage(), contractOrderDTO, JSON.toJSONString(this.userContractDTO));
        }

        MarginForceOrder marginForceOrder = new MarginForceOrder();
        marginForceOrder.setId(result.getData());
        marginForceOrder.setContractName(contractOrderDTO.getContractName());
        marginForceOrder.setOrderDirection(contractOrderDTO.getOrderDirection());
        int priceScale;
        int valueScale;
        if (contractOrderDTO.getContractName() == null) {
            LOGGER.error("contractOrderDTO.getContractName is null, contractOrderDTO:{}, userContractDTO:{}", contractOrderDTO, JSON.toJSONString(this.userContractDTO));
            priceScale = MarginConstant.BTC_FORCE_PRICE_SCALE;
            valueScale = MarginConstant.BTC_FORCE_VALUE_SCALE;
        } else if (contractOrderDTO.getContractName().startsWith(CoinSymbolEnum.BTC.getName())) {
            priceScale = MarginConstant.BTC_FORCE_PRICE_SCALE;
            valueScale = MarginConstant.BTC_FORCE_VALUE_SCALE;
        } else {
            priceScale = MarginConstant.ETH_FORCE_PRICE_SCALE;
            valueScale = MarginConstant.ETH_FORCE_VALUE_SCALE;
        }
        marginForceOrder.setPrice(contractOrderDTO.getPrice().setScale(priceScale, RoundingMode.HALF_UP));
        marginForceOrder.setValue(contractOrderDTO.getTotalAmount().multiply(contractOrderDTO.getPrice()).setScale(valueScale, RoundingMode.HALF_UP));
        marginForceOrder.setTime(System.currentTimeMillis());
        return marginForceOrder;
    }


    /**
     * ???????????????????????????????????????????????????????????????????????????????????????????????????????????????
     *
     * @param dto
     * @return
     */
    public BigDecimal getLatestPrice(UserPositionDTO dto) throws MarginException {
        BigDecimal latestPrice;
        String key;
        if (PositionTypeEnum.OVER.getCode().equals(dto.getPositionType())) {
            // ?????????????????????
            key = dto.getContractId() + PriceDirectionEnum.BUY.getName();
        } else {
            // ?????????????????????
            key = dto.getContractId() + PriceDirectionEnum.SELL.getName();
        }
        latestPrice = latestPriceMap.get(key);
        if (latestPrice == null) {
            throw new MarginException(LogUtils.format("return null get %s from latestPriceMap", key));
        }
        return latestPrice;
    }



    /**
     * ?????????????????????
     *
     * @param userPositionDTOList
     * @return
     */
    public BigDecimal calculatePositionProfit(List<UserPositionDTO> userPositionDTOList) throws MarginException {
        BigDecimal profit = new BigDecimal(0);
        if (CollectionUtils.isEmpty(userPositionDTOList)) {
            return profit;
        }
        for (UserPositionDTO userPositionDTO : userPositionDTOList) {
            profit = profit.add(calculatePositionProfit(userPositionDTO));
        }
        return profit;
    }

    /**
     * ?????????????????????
     *
     * @param dto
     * @return
     */
    public BigDecimal calculatePositionProfitWithoutThrow(UserPositionDTO dto, List<Long> exceptionid) {
        BigDecimal profit = null;
        try {
            if (PositionTypeEnum.OVER.getCode().equals(dto.getPositionType())) {
//                profit = getLatestPrice(dto).subtract(new BigDecimal(dto.getAveragePrice())).multiply(dto.getAmount()).multiply(dto.getContractSize());
                profit = getLatestPrice(dto).subtract(new BigDecimal(dto.getAveragePrice())).multiply(dto.getAmount());
            } else {
//                profit = new BigDecimal(dto.getAveragePrice()).subtract(getLatestPrice(dto)).multiply(dto.getAmount()).multiply(dto.getContractSize());
                profit = new BigDecimal(dto.getAveragePrice()).subtract(getLatestPrice(dto)).multiply(dto.getAmount());
            }
        } catch (MarginException e) {
            exceptionid.add(dto.getId());
            LOGGER.error("calculatePositionProfitWithoutThrow exption!", e);
        }
        if (profit == null) {
            return new BigDecimal(0);
        }
        return profit;
    }

    /**
     * ?????????????????????
     *
     * @param dto
     * @return
     */
    public BigDecimal calculatePositionProfit(UserPositionDTO dto) throws MarginException {
        BigDecimal profit;
        if (PositionTypeEnum.OVER.getCode().equals(dto.getPositionType())) {
//            profit = getLatestPrice(dto).subtract(new BigDecimal(dto.getAveragePrice())).multiply(dto.getAmount()).multiply(dto.getContractSize());
            profit = getLatestPrice(dto).subtract(new BigDecimal(dto.getAveragePrice())).multiply(dto.getAmount());
        } else {
//            profit = new BigDecimal(dto.getAveragePrice()).subtract(getLatestPrice(dto)).multiply(dto.getAmount()).multiply(dto.getContractSize());
            profit = new BigDecimal(dto.getAveragePrice()).subtract(getLatestPrice(dto)).multiply(dto.getAmount());
        }
        return profit;
    }


    /**
     * ?????????????????????????????????,???????????????????????????????????????
     *
     * @param orderDTOList
     * @param positionDTO
     * @return
     */
    public Map<MarginOrderTypeEnum, List<ContractOrderDTO>> filterOrder(List<ContractOrderDTO> orderDTOList, UserPositionDTO positionDTO) throws MarginException {
        Map<MarginOrderTypeEnum, List<ContractOrderDTO>> res = new HashMap<>(2);
        List<ContractOrderDTO> feeOrderList = new ArrayList<>();
        final BigDecimal latestPrice = getLatestPrice(positionDTO);
        //????????????
        BigDecimal amount = positionDTO.getAmount();

        //??????????????????????????????
        BigDecimal orderUnfilledAmount = new BigDecimal(0);

        //??????????????????????????????????????????
        for (ContractOrderDTO contractOrderDTO : orderDTOList) {
            orderUnfilledAmount = orderUnfilledAmount.add(contractOrderDTO.getUnfilledAmount());
        }

        if (orderUnfilledAmount.compareTo(amount) > 0) {
            //???????????????????????????
            orderDTOList.sort(new Comparator<ContractOrderDTO>() {
                @Override
                public int compare(ContractOrderDTO o1, ContractOrderDTO o2) {
                    BigDecimal o1p = o1.getPrice().subtract(latestPrice).abs();
                    BigDecimal o2p = o2.getPrice().subtract(latestPrice).abs();

                    if (o1p.compareTo(o2p) < 0) {
                        return -1;
                    } else if (o1p.compareTo(o2p) > 0) {
                        return 1;
                    } else {
                        return 0;
                    }
                }
            });

            //?????????????????????????????????,????????????????????????????????????
            Iterator<ContractOrderDTO> iterator = orderDTOList.iterator();
            ContractOrderDTO feeOrder;
            while (iterator.hasNext()) {
                ContractOrderDTO dto = iterator.next();
                feeOrder = new ContractOrderDTO();
                BeanUtils.copyProperties(dto, feeOrder);
                if (dto.getUnfilledAmount().compareTo(amount) <= 0) {
                    amount = amount.subtract(dto.getUnfilledAmount());
                    iterator.remove();
                    feeOrderList.add(feeOrder);
                } else {
                    dto.setUnfilledAmount(dto.getUnfilledAmount().subtract(amount));
                    feeOrder.setUnfilledAmount(amount);
                    feeOrderList.add(feeOrder);
                    //?????????????????? ????????????
                    break;
                }
            }
            res.put(MarginOrderTypeEnum.MARGIN, orderDTOList);
            res.put(MarginOrderTypeEnum.FEE, feeOrderList);
        } else {
            //???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
            res.put(MarginOrderTypeEnum.MARGIN, new ArrayList<>());
            res.put(MarginOrderTypeEnum.FEE, orderDTOList);
        }
        return res;
    }

    /**
     * ?????????????????????
     *
     * @param contractOrderDTOList
     * @param customLeverage
     * @return
     */
    public BigDecimal calculateOrderMargin(List<ContractOrderDTO> contractOrderDTOList, BigDecimal customLeverage) throws MarginException {
        BigDecimal orderMargin = new BigDecimal(0);
        if (CollectionUtils.isEmpty(contractOrderDTOList)) {
            return orderMargin;
        }
        for (ContractOrderDTO dto : contractOrderDTOList) {
            try {
                // ????????????????????????????????????
                // ??????????????????????????????
                orderMargin = orderMargin.add(dto.getUnfilledAmount().abs().multiply(dto.getPrice()).divide(customLeverage, SCALE, RoundingMode.HALF_UP).multiply(new BigDecimal(1).add(dto.getFee())));
            } catch (Exception e) {
                throw new MarginException(LogUtils.format("calculateOrderMargin Exception, contractOrderDTO: %s, customLeverage: %s", JSON.toJSONString(dto), customLeverage.toString()));
            }
        }
        return orderMargin;
    }

    /**
     * ?????????????????????
     * @param contractOrderDTOList
     * @param customLeverage
     * @return
     */
    public BigDecimal calculateOrderFee(List<ContractOrderDTO> contractOrderDTOList, BigDecimal customLeverage) throws MarginException {
        BigDecimal orderFee = new BigDecimal(0);
        if (CollectionUtils.isEmpty(contractOrderDTOList)) {
            return orderFee;
        }
        for (ContractOrderDTO dto : contractOrderDTOList) {
            try {
                // ????????????????????????????????????
                // ??????????????????????????????
                orderFee = orderFee.add(dto.getUnfilledAmount().abs().multiply(dto.getPrice()).divide(customLeverage, SCALE, RoundingMode.HALF_UP).multiply(dto.getFee()));
            } catch (Exception e) {
                throw new MarginException(LogUtils.format("calculateOrderFee Exception, contractOrderDTO: %s, customLeverage: %s", JSON.toJSONString(dto), customLeverage.toString()));
            }
        }
        return orderFee;
    }

    /**
     * ??????????????????????????????????????????
     *
     * @param userPositionDTO
     * @return
     */
    public BigDecimal getCustomLeverage(UserPositionDTO userPositionDTO) throws MarginException {
        BigDecimal bigDecimal = null;
        try {
            bigDecimal = new BigDecimal(this.userIdAssetIdLeverMapMap.get(userPositionDTO.getUserId()).get(this.contractCategoryDTOMap.get(userPositionDTO.getContractId()).getAssetId()));
        } catch (Exception e) {
            Map<Integer, Integer> map = this.userIdAssetIdLeverMapMap.get(userPositionDTO.getUserId());
            if (MapUtils.isEmpty(map)) {
                throw new MarginException(LogUtils.format("return empty get %s from userIdAssetIdLeverMapMap!", String.valueOf(userPositionDTO.getUserId())), e);
            }

            ContractCategoryDTO contractCategoryDTO = this.contractCategoryDTOMap.get(userPositionDTO.getContractId());
            if (contractCategoryDTO == null) {
                throw new MarginException(LogUtils.format("return empty get %s from contractCategoryDTOMap!", String.valueOf(userPositionDTO.getContractId())), e);
            }

            if (contractCategoryDTO.getAssetId() == null) {
                throw new MarginException(LogUtils.format("contractCategoryDTO.getAssetId return null! contractCategoryDTO: %s", JSON.toJSONString(contractCategoryDTO)), e);
            }

            if (map.get(contractCategoryDTO.getAssetId()) == null) {
                throw new MarginException(LogUtils.format("return null get %s from get %s from userIdAssetIdLeverMapMap!", String.valueOf(contractCategoryDTO.getAssetId()), String.valueOf(userPositionDTO.getUserId())), e);
            }

            throw new MarginException("getCustomLeverage exception", e);
        }
        return bigDecimal;
    }

    /**
     * ?????????????????????
     *
     * @param userPositionDTOList
     * @return
     */
    public BigDecimal calculatePositionMargin(List<UserPositionDTO> userPositionDTOList) throws MarginException {
        BigDecimal positionValue = new BigDecimal(0);
        if (CollectionUtils.isEmpty(userPositionDTOList)) {
            return positionValue;
        }
        for (UserPositionDTO userPositionDTO : userPositionDTOList) {
            positionValue = positionValue.add(calculatePositionMargin(userPositionDTO));
        }
        return positionValue;
    }

    /**
     * ?????????????????????
     *
     * @param userPositionDTO
     * @return
     */
    public BigDecimal calculatePositionMargin(UserPositionDTO userPositionDTO) throws MarginException {
        if (userPositionDTO == null) {
            return new BigDecimal(0);
        }
        BigDecimal customLeverage = getCustomLeverage(userPositionDTO);
        return calculateSinglePositionValue(userPositionDTO).divide(customLeverage, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * ??????????????????
     *
     * @param userPositionDTOList
     * @return
     */
    public BigDecimal calculatePositionValue(List<UserPositionDTO> userPositionDTOList) throws MarginException {
        BigDecimal positionValue = new BigDecimal(0);
        if (CollectionUtils.isEmpty(userPositionDTOList)) {
            return positionValue;
        }
        for (UserPositionDTO userPositionDTO : userPositionDTOList) {
            positionValue = positionValue.add(calculateSinglePositionValue(userPositionDTO));
        }
        return positionValue;
    }

    /**
     * ?????????????????????
     *
     * @param userPositionDTO
     * @return
     */
    public BigDecimal calculateSinglePositionValue(UserPositionDTO userPositionDTO) throws MarginException {
        BigDecimal positionValue = new BigDecimal(0);
        if (userPositionDTO == null) {
            return positionValue;
        }
        BigDecimal latestPrice = getLatestPrice(userPositionDTO);
        //??????????????????
        //?????????????????? ???????????? = |??????????????????| * ?????????????????? * ??????????????????????????? = |??????????????????| * ?????????????????? * ????????????
//        positionValue = positionValue.add(userPositionDTO.getContractSize().multiply(userPositionDTO.getAmount().abs()).multiply(latestPrice));
        positionValue = positionValue.add(userPositionDTO.getAmount().abs().multiply(latestPrice));

        return positionValue;
    }

    private BigDecimal calculateTargetPrice(UserPositionDTO userPositionDTO, BigDecimal totalPositionMargin, BigDecimal totalRights) throws MarginException {
        BigDecimal latestPrice = this.getLatestPrice(userPositionDTO);
        //???????????????????????????
        BigDecimal tempPrice = this.calculatePositionMargin(userPositionDTO).divide(totalPositionMargin, SCALE, RoundingMode.HALF_UP).multiply(totalRights).divide(userPositionDTO.getAmount(), SCALE, RoundingMode.HALF_UP);
        BigDecimal zeroingPrice;
        if (PositionTypeEnum.OVER.getCode().equals(userPositionDTO.getPositionType())) {
            zeroingPrice = latestPrice.subtract(tempPrice);
            return MarginConstant.LONG_MULTIPLE.multiply(latestPrice).max(zeroingPrice);
        } else {
            zeroingPrice = latestPrice.add(tempPrice);
            return MarginConstant.SHORT_MULTIPLE.multiply(latestPrice).min(zeroingPrice);
        }
    }


    /**
     * ???????????????????????????????????????
     *
     * @param contractOrderDTOList
     * @return
     */
    public Map<Long, Map<Integer, List<ContractOrderDTO>>> classifyOrder(List<ContractOrderDTO> contractOrderDTOList) {
        Map<Long, Map<Integer, List<ContractOrderDTO>>> orderListMapMap = new HashMap<>();
        if (CollectionUtils.isEmpty(contractOrderDTOList)) {
            return orderListMapMap;
        }
        List<ContractOrderDTO> orderList;
        Map<Integer, List<ContractOrderDTO>> orderListMap;
        for (ContractOrderDTO dto : contractOrderDTOList) {
            if (orderListMapMap.containsKey(dto.getContractId())) {
                orderListMap = orderListMapMap.get(dto.getContractId());
                if (!orderListMap.containsKey(dto.getOrderDirection())) {
                    orderList = new ArrayList<>();
                    orderList.add(dto);
                    orderListMap.put(dto.getOrderDirection(), orderList);
                } else {
                    orderListMap.get(dto.getOrderDirection()).add(dto);
                }
            } else {
                orderList = new ArrayList<>();
                orderList.add(dto);
                orderListMap = new HashMap<>();
                orderListMap.put(dto.getOrderDirection(), orderList);
                orderListMapMap.put(dto.getContractId(), orderListMap);
            }
        }
        return orderListMapMap;
    }

    /**
     * ???????????????????????????????????????
     *
     * @param userPositionDTOList
     * @return
     */
    public Map<Long, Map<Integer, UserPositionDTO>> classifyPosition(List<UserPositionDTO> userPositionDTOList) {
        Map<Long, Map<Integer, UserPositionDTO>> positionMapMap = new HashMap<>();
        if (CollectionUtils.isEmpty(userPositionDTOList)) {
            return positionMapMap;
        }
        Map<Integer, UserPositionDTO> positionMap;
        for (UserPositionDTO dto : userPositionDTOList) {
            //??????contractId??????????????????-???????????????
            positionMap = new HashMap<>();
            positionMap.put(dto.getPositionType(), dto);
            positionMapMap.put(dto.getContractId(), positionMap);
//            if (positionMapMap.containsKey(dto.getContractId())) {
//                positionMap = positionMapMap.get(dto.getContractId());
//                positionMap.put(dto.getPositionType(), dto);
//            } else {
//                positionMap = new HashMap<>();
//                positionMap.put(dto.getPositionType(), dto);
//                positionMapMap.put(dto.getContractId(), positionMap);
//            }
        }
        return positionMapMap;
    }

    /**
     * ???????????????????????????????????????
     *
     * @param userContractDTO
     * @return
     */
    public Boolean isAllForcedOrderDone(UserContractDTO userContractDTO) throws MarginException {
        BaseQuery baseQuery = new BaseQuery();
        baseQuery.setPageNo(1);
        baseQuery.setPageSize(1);
        baseQuery.setUserId(userContractDTO.getUserId());
        List<Integer> orderStatus = new ArrayList<>();
        orderStatus.add(OrderStatusEnum.COMMIT.getCode());
        orderStatus.add(OrderStatusEnum.PART_MATCH.getCode());
        baseQuery.setOrderStatus(orderStatus);
        baseQuery.setOrderType(OrderTypeEnum.ENFORCE.getCode());
        Page<ContractOrderDTO> contractOrderDTOPage = null;
        contractOrderDTOPage = contractOrderService.listContractOrderByQuery(baseQuery);

        if (contractOrderDTOPage == null) {
            LOGGER.error("contractOrderService.listContractOrderByQuery return null! baseQuery:{}", baseQuery);
            throw new MarginException("contractOrderService.listContractOrderByQuery return null!");
        }

        if (CollectionUtils.isEmpty(contractOrderDTOPage.getData())) {
            return true;
        }
        return false;
    }

    /**
     * ????????????
     * @param userPositionDTOList
     * @return
     */
    public List<BigDecimal> getIndexPrice(List<UserPositionDTO> userPositionDTOList) {
        List<BigDecimal> list = new ArrayList<>();
        try {
            if (CollectionUtils.isEmpty(userPositionDTOList)) {
                return list;
            }
            for (UserPositionDTO userPositionDTO : userPositionDTOList) {
                list.add(this.getLatestPrice(userPositionDTO));
            }
        } catch (MarginException e) {
            LOGGER.error("getIndexPrice e:", e);
        }
        return list;
    }

    /**
     * ????????????
     * @param positionMapMap
     * @return
     */
    public List<BigDecimal> getIndexPrice(Map<Long, Map<Integer, UserPositionDTO>> positionMapMap) {
        List<BigDecimal> list = new ArrayList<>();
        try {
            if (MapUtils.isEmpty(positionMapMap)) {
                return list;
            }
            for (Map<Integer, UserPositionDTO> map : positionMapMap.values()) {
                if (MapUtils.isEmpty(map)) {
                    return list;
                }
                list.addAll(this.getIndexPrice(new ArrayList<>(map.values())));
            }
        } catch (Exception e) {
            LOGGER.error("getIndexPrice e:", e);
        }
        return list;
    }
}
