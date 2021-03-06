package com.ruyuan.eshop.order.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.ruyuan.eshop.common.constants.RedisLockKeyConstants;
import com.ruyuan.eshop.common.constants.RocketMqConstant;
import com.ruyuan.eshop.common.core.JsonResult;
import com.ruyuan.eshop.common.enums.*;
import com.ruyuan.eshop.common.exception.BaseBizException;
import com.ruyuan.eshop.common.message.ActualRefundMessage;
import com.ruyuan.eshop.common.redis.RedisLock;
import com.ruyuan.eshop.common.utils.ParamCheckUtil;
import com.ruyuan.eshop.common.utils.RandomUtil;
import com.ruyuan.eshop.customer.domain.request.CustomerReceiveAfterSaleRequest;
import com.ruyuan.eshop.market.domain.request.ReleaseUserCouponRequest;
import com.ruyuan.eshop.order.converter.AfterSaleConverter;
import com.ruyuan.eshop.order.converter.OrderConverter;
import com.ruyuan.eshop.order.dao.*;
import com.ruyuan.eshop.order.domain.dto.AfterSaleOrderItemDTO;
import com.ruyuan.eshop.order.domain.dto.CancelOrderRefundAmountDTO;
import com.ruyuan.eshop.order.domain.dto.OrderInfoDTO;
import com.ruyuan.eshop.order.domain.dto.OrderItemDTO;
import com.ruyuan.eshop.order.domain.entity.*;
import com.ruyuan.eshop.order.domain.request.*;
import com.ruyuan.eshop.order.enums.*;
import com.ruyuan.eshop.order.exception.OrderBizException;
import com.ruyuan.eshop.order.exception.OrderErrorCodeEnum;
import com.ruyuan.eshop.order.manager.OrderNoManager;
import com.ruyuan.eshop.order.mq.producer.DefaultProducer;
import com.ruyuan.eshop.order.remote.PayRemote;
import com.ruyuan.eshop.order.manager.AfterSaleManager;
import com.ruyuan.eshop.order.service.OrderAfterSaleService;
import com.ruyuan.eshop.pay.domain.request.PayRefundRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * @author zhonghuashishan
 * @version 1.0
 */
@Service
@Slf4j
public class OrderAfterSaleServiceImpl implements OrderAfterSaleService {

    @Autowired
    private OrderPaymentDetailDAO orderPaymentDetailDAO;

    @Autowired
    private RedisLock redisLock;

    @Autowired
    private PayRemote payRemote;

    @Autowired
    private OrderInfoDAO orderInfoDAO;

    @Autowired
    private OrderNoManager orderNoManager;

    @Autowired
    private AfterSaleInfoDAO afterSaleInfoDAO;

    @Autowired
    private OrderItemDAO orderItemDAO;

    @Autowired
    private AfterSaleLogDAO afterSaleLogDAO;

    @Autowired
    private AfterSaleRefundDAO afterSaleRefundDAO;

    @Autowired
    private DefaultProducer defaultProducer;

    @Autowired
    private AfterSaleItemDAO afterSaleItemDAO;

    @Autowired
    private OrderAmountDAO orderAmountDAO;

    @Autowired
    private AfterSaleOperateLogFactory afterSaleOperateLogFactory;

    @Autowired
    private AfterSaleManager afterSaleManager;

    @Autowired
    private OrderConverter orderConverter;

    @Autowired
    private AfterSaleConverter afterSaleConverter;

    /**
     * ????????????/?????????????????????
     */
    @Override
    public JsonResult<Boolean> cancelOrder(CancelOrderRequest cancelOrderRequest) {
        //  ????????????
        checkCancelOrderRequestParam(cancelOrderRequest);
        //  ????????????
        String orderId = cancelOrderRequest.getOrderId();
        String key = RedisLockKeyConstants.CANCEL_KEY + orderId;
        boolean lock = redisLock.tryLock(key);
        if (!lock) {
            throw new OrderBizException(OrderErrorCodeEnum.CANCEL_ORDER_REPEAT);
        }
        try {
            //  ??????????????????
            return executeCancelOrder(cancelOrderRequest, orderId);
        } catch (Exception e) {
            log.error("biz error", e);
            throw new OrderBizException(e.getMessage());
        } finally {
            redisLock.unlock(key);
        }
    }

    @Override
    public JsonResult<Boolean> executeCancelOrder(CancelOrderRequest cancelOrderRequest, String orderId) {
        // 1???????????????
        OrderInfoDO orderInfoDO = findOrderInfo(orderId);
        CancelOrderAssembleRequest cancelOrderAssembleRequest = buildAssembleRequest(orderId, cancelOrderRequest, orderInfoDO);
        if (cancelOrderAssembleRequest.getOrderInfoDTO().getOrderStatus() >= OrderStatusEnum.OUT_STOCK.getCode()) {
            throw new OrderBizException(OrderErrorCodeEnum.CURRENT_ORDER_STATUS_CANNOT_CANCEL);
        }

        TransactionMQProducer producer = defaultProducer.getProducer();
        producer.setTransactionListener(new TransactionListener() {
            @Override
            public LocalTransactionState executeLocalTransaction(Message message, Object o) {
                try {
                    //  2?????????????????????????????????????????????????????????????????????
                    afterSaleManager.cancelOrderFulfillmentAndUpdateOrderStatus(cancelOrderAssembleRequest);
                    return LocalTransactionState.COMMIT_MESSAGE;
                } catch (Exception e) {
                    log.error("system error", e);
                    return LocalTransactionState.ROLLBACK_MESSAGE;
                }
            }

            @Override
            public LocalTransactionState checkLocalTransaction(MessageExt messageExt) {
                //  ????????????????????????????????????"?????????"
                OrderInfoDO orderInfoByDatabase = orderInfoDAO.getByOrderId(orderId);
                if (OrderStatusEnum.CANCELED.getCode().equals(orderInfoByDatabase.getOrderStatus())) {
                    return LocalTransactionState.COMMIT_MESSAGE;
                }
                return LocalTransactionState.ROLLBACK_MESSAGE;
            }
        });

        try {
            Message message = new Message(RocketMqConstant.RELEASE_ASSETS_TOPIC,
                    JSONObject.toJSONString(cancelOrderAssembleRequest).getBytes(StandardCharsets.UTF_8));
            // 3????????????????????? ??????????????????
            TransactionSendResult result = producer.sendMessageInTransaction(message, cancelOrderAssembleRequest);
            if (!result.getLocalTransactionState().equals(LocalTransactionState.COMMIT_MESSAGE)) {
                throw new OrderBizException(OrderErrorCodeEnum.CANCEL_ORDER_PROCESS_FAILED);
            }
            return JsonResult.buildSuccess(true);
        } catch (Exception e) {
            throw new OrderBizException(OrderErrorCodeEnum.SEND_TRANSACTION_MQ_FAILED);
        }
    }

    /**
     * ?????? ???????????? ??????
     */
    private CancelOrderAssembleRequest buildAssembleRequest(String orderId, CancelOrderRequest cancelOrderRequest, OrderInfoDO orderInfoDO) {
        Integer cancelType = cancelOrderRequest.getCancelType();
        OrderInfoDTO orderInfoDTO = orderConverter.orderInfoDO2DTO(orderInfoDO);
        orderInfoDTO.setCancelType(String.valueOf(cancelType));
        List<OrderItemDTO> orderItemDTOList = findOrderItemInfo(orderId);
        CancelOrderAssembleRequest cancelOrderAssembleRequest = orderConverter.convertCancelOrderRequest(cancelOrderRequest);
        cancelOrderAssembleRequest.setOrderId(orderId);
        cancelOrderAssembleRequest.setOrderInfoDTO(orderInfoDTO);
        cancelOrderAssembleRequest.setOrderItemDTOList(orderItemDTOList);
        cancelOrderAssembleRequest.setAfterSaleType(AfterSaleTypeEnum.RETURN_MONEY.getCode());

        return cancelOrderAssembleRequest;
    }


    /**
     * ??????????????????
     */
    private List<OrderItemDTO> findOrderItemInfo(String orderId) {
        List<OrderItemDO> orderItemDOList = orderItemDAO.listByOrderId(orderId);
        if (orderItemDOList == null) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_ITEM_IS_NULL);
        }
        return orderConverter.orderItemDO2DTO(orderItemDOList);
    }

    /**
     * ??????????????????
     */
    private OrderInfoDO findOrderInfo(String orderId) {
        OrderInfoDO orderInfoDO = orderInfoDAO.getByOrderId(orderId);
        if (orderInfoDO == null) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_NOT_FOUND);
        }
        return orderInfoDO;
    }

    /**
     * ????????????????????????????????????
     */
    public void updatePaymentRefundCallbackAfterSale(RefundCallbackRequest payRefundCallbackRequest,
                                                     Integer afterSaleStatus, Integer refundStatus, String refundStatusMsg) {
        Long afterSaleId = Long.valueOf(payRefundCallbackRequest.getAfterSaleId());
        //  ?????? ???????????????
        afterSaleInfoDAO.updateStatus(afterSaleId, AfterSaleStatusEnum.REFUNDING.getCode(), afterSaleStatus);

        //  ?????? ??????????????????
        AfterSaleInfoDO afterSaleInfoDO = afterSaleInfoDAO.getOneByAfterSaleId(afterSaleId);
        AfterSaleLogDO afterSaleLogDO = afterSaleOperateLogFactory.get(afterSaleInfoDO,
                AfterSaleStatusChangeEnum.getBy(AfterSaleStatusEnum.REFUNDING.getCode(), afterSaleStatus));

        afterSaleLogDAO.save(afterSaleLogDO);

        //  ?????? ??????????????????
        AfterSaleRefundDO afterSaleRefundDO = new AfterSaleRefundDO();
        afterSaleRefundDO.setAfterSaleId(payRefundCallbackRequest.getAfterSaleId());
        afterSaleRefundDO.setRefundStatus(refundStatus);
        afterSaleRefundDO.setRefundPayTime(payRefundCallbackRequest.getRefundTime());
        afterSaleRefundDO.setRemark(refundStatusMsg);

        afterSaleRefundDAO.updateAfterSaleRefundStatus(afterSaleRefundDO);
    }

    private void insertReturnGoodsAfterSaleLogTable(String afterSaleId, Integer preAfterSaleStatus, Integer currentAfterSaleStatus) {

        AfterSaleLogDO afterSaleLogDO = new AfterSaleLogDO();
        afterSaleLogDO.setAfterSaleId(afterSaleId);
        afterSaleLogDO.setPreStatus(preAfterSaleStatus);
        afterSaleLogDO.setCurrentStatus(currentAfterSaleStatus);
        //  ????????????????????????
        afterSaleLogDO.setRemark(ReturnGoodsTypeEnum.AFTER_SALE_RETURN_GOODS.getMsg());

        afterSaleLogDAO.save(afterSaleLogDO);
        log.info("???????????????????????????, ????????????:{},??????:PreStatus{},CurrentStatus:{}", afterSaleLogDO.getAfterSaleId(),
                afterSaleLogDO.getPreStatus(), afterSaleLogDO.getCurrentStatus());
    }

    /**
     * ?????????????????????
     */
    public void updateAfterSaleStatus(AfterSaleInfoDO afterSaleInfoDO, Integer fromStatus, Integer toStatus) {
        //  ?????? ???????????????
        afterSaleInfoDAO.updateStatus(afterSaleInfoDO.getAfterSaleId(), fromStatus, toStatus);

        //  ?????? ??????????????????
        AfterSaleLogDO afterSaleLogDO = afterSaleOperateLogFactory.get(afterSaleInfoDO, AfterSaleStatusChangeEnum.getBy(fromStatus, toStatus));
        log.info("????????????????????????,????????????:{},fromStatus:{}, toStatus:{}", afterSaleInfoDO.getAfterSaleId(), fromStatus, toStatus);

        afterSaleLogDAO.save(afterSaleLogDO);
    }


    /**
     * ?????????????????? ?????????????????????
     */
    private void insertReturnGoodsAfterSaleInfoTable(OrderInfoDO orderInfoDO, Integer afterSaleType,
                                                     Integer cancelOrderAfterSaleStatus, AfterSaleInfoDO afterSaleInfoDO,
                                                     String afterSaleId) {

        afterSaleInfoDO.setAfterSaleId(Long.valueOf(afterSaleId));
        afterSaleInfoDO.setBusinessIdentifier(BusinessIdentifierEnum.SELF_MALL.getCode());
        afterSaleInfoDO.setOrderId(orderInfoDO.getOrderId());
        afterSaleInfoDO.setOrderSourceChannel(BusinessIdentifierEnum.SELF_MALL.getCode());
        afterSaleInfoDO.setUserId(orderInfoDO.getUserId());
        afterSaleInfoDO.setOrderType(OrderTypeEnum.NORMAL.getCode());
        afterSaleInfoDO.setApplyTime(new Date());
        afterSaleInfoDO.setAfterSaleStatus(cancelOrderAfterSaleStatus);
        //  ??????????????????????????????
        afterSaleInfoDO.setApplySource(AfterSaleApplySourceEnum.USER_RETURN_GOODS.getCode());
        afterSaleInfoDO.setRemark(ReturnGoodsTypeEnum.AFTER_SALE_RETURN_GOODS.getMsg());
        afterSaleInfoDO.setApplyReasonCode(AfterSaleReasonEnum.USER.getCode());
        afterSaleInfoDO.setApplyReason(AfterSaleReasonEnum.USER.getMsg());
        afterSaleInfoDO.setAfterSaleTypeDetail(AfterSaleTypeDetailEnum.PART_REFUND.getCode());

        //  ???????????? ???????????????????????????
        if (AfterSaleTypeEnum.RETURN_GOODS.getCode().equals(afterSaleType)) {
            afterSaleInfoDO.setAfterSaleType(AfterSaleTypeEnum.RETURN_GOODS.getCode());
        }
        //  ???????????? ???????????????????????? ????????????????????????????????????
        if (AfterSaleTypeEnum.RETURN_MONEY.getCode().equals(afterSaleType)) {
            afterSaleInfoDO.setAfterSaleType(AfterSaleTypeEnum.RETURN_MONEY.getCode());
        }

        afterSaleInfoDAO.save(afterSaleInfoDO);

        log.info("????????????????????????,?????????:{},????????????:{},??????????????????:{}", orderInfoDO.getOrderId(), afterSaleId,
                afterSaleInfoDO.getAfterSaleStatus());
    }


    /**
     * ????????????
     *
     * @param cancelOrderRequest ??????????????????
     */
    private void checkCancelOrderRequestParam(CancelOrderRequest cancelOrderRequest) {
        ParamCheckUtil.checkObjectNonNull(cancelOrderRequest);

        //  ????????????
        Integer orderStatus = cancelOrderRequest.getOrderStatus();
        ParamCheckUtil.checkObjectNonNull(orderStatus, OrderErrorCodeEnum.ORDER_STATUS_IS_NULL);

        if (orderStatus.equals(OrderStatusEnum.CANCELED.getCode())) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_STATUS_CANCELED);
        }

        if (orderStatus >= OrderStatusEnum.OUT_STOCK.getCode()) {
            throw new OrderBizException(OrderErrorCodeEnum.ORDER_STATUS_CHANGED);
        }

        //  ??????ID
        String orderId = cancelOrderRequest.getOrderId();
        ParamCheckUtil.checkStringNonEmpty(orderId, OrderErrorCodeEnum.CANCEL_ORDER_ID_IS_NULL);

        //  ???????????????
        Integer businessIdentifier = cancelOrderRequest.getBusinessIdentifier();
        ParamCheckUtil.checkObjectNonNull(businessIdentifier, OrderErrorCodeEnum.BUSINESS_IDENTIFIER_IS_NULL);

        //  ??????????????????
        Integer cancelType = cancelOrderRequest.getCancelType();
        ParamCheckUtil.checkObjectNonNull(cancelType, OrderErrorCodeEnum.CANCEL_TYPE_IS_NULL);

        //  ??????ID
        String userId = cancelOrderRequest.getUserId();
        ParamCheckUtil.checkStringNonEmpty(userId, OrderErrorCodeEnum.USER_ID_IS_NULL);

        //  ????????????
        Integer orderType = cancelOrderRequest.getOrderType();
        ParamCheckUtil.checkObjectNonNull(orderType, OrderErrorCodeEnum.ORDER_TYPE_IS_NULL);

    }

    public CancelOrderRefundAmountDTO calculatingCancelOrderRefundAmount(CancelOrderAssembleRequest cancelOrderAssembleRequest) {
        OrderInfoDTO orderInfoDTO = cancelOrderAssembleRequest.getOrderInfoDTO();
        CancelOrderRefundAmountDTO cancelOrderRefundAmountDTO = new CancelOrderRefundAmountDTO();

        //  ????????????????????????????????????
        cancelOrderRefundAmountDTO.setOrderId(orderInfoDTO.getOrderId());
        cancelOrderRefundAmountDTO.setTotalAmount(orderInfoDTO.getTotalAmount());
        cancelOrderRefundAmountDTO.setReturnGoodAmount(orderInfoDTO.getPayAmount());

        return cancelOrderRefundAmountDTO;
    }

    /**
     * ????????????
     * todo ??????
     *
     * @return
     */
    private JsonResult<BigDecimal> lackRefund(String orderId, Long lackAfterSaleId) {
        AfterSaleInfoDO lackAfterSaleInfo = afterSaleInfoDAO.getOneByAfterSaleId(lackAfterSaleId);
        return JsonResult.buildSuccess(new BigDecimal(lackAfterSaleInfo.getRealRefundAmount()));
    }

    @Override
    public JsonResult<Boolean> processCancelOrder(CancelOrderAssembleRequest cancelOrderAssembleRequest) {
        String orderId = cancelOrderAssembleRequest.getOrderId();
        //  ????????????
        String key = RedisLockKeyConstants.REFUND_KEY + orderId;
        try {
            boolean lock = redisLock.tryLock(key);
            if (!lock) {
                throw new OrderBizException(OrderErrorCodeEnum.PROCESS_REFUND_REPEAT);
            }

            //  ??????????????????????????????
            //  ?????????????????????
            OrderInfoDTO orderInfoDTO = cancelOrderAssembleRequest.getOrderInfoDTO();
            OrderInfoDO orderInfoDO = orderConverter.orderInfoDTO2DO(orderInfoDTO);
            String afterSaleId = orderNoManager.genOrderId(OrderNoTypeEnum.AFTER_SALE.getCode(), orderInfoDO.getUserId());

            //  1????????? ???????????? ????????????
            CancelOrderRefundAmountDTO cancelOrderRefundAmountDTO = calculatingCancelOrderRefundAmount(cancelOrderAssembleRequest);
            cancelOrderAssembleRequest.setCancelOrderRefundAmountDTO(cancelOrderRefundAmountDTO);

            TransactionMQProducer producer = defaultProducer.getProducer();
            producer.setTransactionListener(new TransactionListener() {
                @Override
                public LocalTransactionState executeLocalTransaction(Message message, Object o) {
                    try {
                        //  2????????????????????? ??????????????????
                        afterSaleManager.insertCancelOrderAfterSale(cancelOrderAssembleRequest, AfterSaleStatusEnum.REVIEW_PASS.getCode(),
                                orderInfoDO, afterSaleId);
                        return LocalTransactionState.COMMIT_MESSAGE;
                    } catch (Exception e) {
                        log.error("system error", e);
                        return LocalTransactionState.ROLLBACK_MESSAGE;
                    }
                }

                @Override
                public LocalTransactionState checkLocalTransaction(MessageExt messageExt) {
                    //  ????????????????????????????????????
                    AfterSaleInfoDO afterSaleInfoDO = afterSaleInfoDAO.getOneByAfterSaleId(Long.valueOf(afterSaleId));
                    List<AfterSaleItemDO> afterSaleItemDOList = afterSaleItemDAO.listByAfterSaleId(Long.valueOf(afterSaleId));
                    List<AfterSaleLogDO> afterSaleLogDOList = afterSaleLogDAO.listByAfterSaleId(Long.valueOf(afterSaleId));
                    List<AfterSaleRefundDO> afterSaleRefundDOList = afterSaleRefundDAO.listByAfterSaleId(Long.valueOf(afterSaleId));
                    if (afterSaleInfoDO != null
                            && !afterSaleItemDOList.isEmpty()
                            && !afterSaleLogDOList.isEmpty()
                            && !afterSaleRefundDOList.isEmpty()) {
                        return LocalTransactionState.COMMIT_MESSAGE;
                    }
                    return LocalTransactionState.ROLLBACK_MESSAGE;
                }
            });

            try {
                //  3???????????????MQ??????
                ActualRefundMessage actualRefundMessage = new ActualRefundMessage();
                actualRefundMessage.setOrderId(cancelOrderAssembleRequest.getOrderId());
                actualRefundMessage.setLastReturnGoods(cancelOrderAssembleRequest.isLastReturnGoods());
                actualRefundMessage.setAfterSaleId(Long.valueOf(afterSaleId));
                Message message = new Message(RocketMqConstant.ACTUAL_REFUND_TOPIC,
                        JSONObject.toJSONString(actualRefundMessage).getBytes(StandardCharsets.UTF_8));

                // 4???????????????MQ??????
                TransactionSendResult result = producer.sendMessageInTransaction(message, actualRefundMessage);
                if (!result.getLocalTransactionState().equals(LocalTransactionState.COMMIT_MESSAGE)) {
                    throw new OrderBizException(OrderErrorCodeEnum.PROCESS_REFUND_FAILED);
                }
                return JsonResult.buildSuccess(true);
            } catch (Exception e) {
                throw new OrderBizException(OrderErrorCodeEnum.SEND_TRANSACTION_MQ_FAILED);
            }
        } finally {
            redisLock.unlock(key);
        }
    }


    @Override
    public JsonResult<Boolean> sendRefundMobileMessage(String orderId) {
        log.info("?????????????????????,?????????:{}", orderId);
        return JsonResult.buildSuccess();
    }

    @Override
    public JsonResult<Boolean> sendRefundAppMessage(String orderId) {
        log.info("???????????????APP??????,?????????:{}", orderId);
        return JsonResult.buildSuccess();
    }

    @Override
    public JsonResult<Boolean> refundMoney(ActualRefundMessage actualRefundMessage) {
        Long afterSaleId = actualRefundMessage.getAfterSaleId();
        String key = RedisLockKeyConstants.REFUND_KEY + afterSaleId;
        try {
            boolean lock = redisLock.tryLock(key);
            if (!lock) {
                throw new OrderBizException(OrderErrorCodeEnum.REFUND_MONEY_REPEAT);
            }
            AfterSaleInfoDO afterSaleInfoDO = afterSaleInfoDAO.getOneByAfterSaleId(actualRefundMessage.getAfterSaleId());
            AfterSaleRefundDO afterSaleRefundDO = afterSaleRefundDAO.findAfterSaleRefundByfterSaleId(String.valueOf(afterSaleId));

            //  1??????????????????????????????????????????
            PayRefundRequest payRefundRequest = buildPayRefundRequest(actualRefundMessage, afterSaleRefundDO);

            //  2???????????????
            payRemote.executeRefund(payRefundRequest);

            //  3???????????????????????????????????????????????????????????????????????????MQ????????????,??????isLastReturnGoods?????????true
            if (actualRefundMessage.isLastReturnGoods()) {
                TransactionMQProducer producer = defaultProducer.getProducer();
                //  ????????????MQ?????????
                ReleaseUserCouponRequest releaseUserCouponRequest = buildLastOrderReleasesCouponMessage(producer, afterSaleInfoDO,
                        afterSaleId, actualRefundMessage);
                try {
                    // 4????????????????????? ???????????????
                    Message message = new Message(RocketMqConstant.CANCEL_RELEASE_PROPERTY_TOPIC,
                            JSONObject.toJSONString(releaseUserCouponRequest).getBytes(StandardCharsets.UTF_8));
                    TransactionSendResult result = producer.sendMessageInTransaction(message, releaseUserCouponRequest);
                    if (!result.getLocalTransactionState().equals(LocalTransactionState.COMMIT_MESSAGE)) {
                        throw new OrderBizException(OrderErrorCodeEnum.REFUND_MONEY_RELEASE_COUPON_FAILED);
                    }
                    return JsonResult.buildSuccess(true);
                } catch (Exception e) {
                    throw new OrderBizException(OrderErrorCodeEnum.SEND_TRANSACTION_MQ_FAILED);
                }
            } else {
                //  ????????????????????????????????????????????? ??? ????????????,???????????????????????????????????????
                //  ?????????????????????
                updateAfterSaleStatus(afterSaleInfoDO, AfterSaleStatusEnum.REVIEW_PASS.getCode(), AfterSaleStatusEnum.REFUNDING.getCode());
                return JsonResult.buildSuccess(true);
            }

        } catch (OrderBizException e) {
            log.error("system error", e);
            return JsonResult.buildError(e.getMessage());
        } finally {
            redisLock.unlock(key);
        }
    }


    private ReleaseUserCouponRequest buildLastOrderReleasesCouponMessage(TransactionMQProducer producer, AfterSaleInfoDO afterSaleInfoDO,
                                                                         Long afterSaleId, ActualRefundMessage actualRefundMessage) {
        producer.setTransactionListener(new TransactionListener() {
            @Override
            public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
                try {
                    //  ?????????????????????
                    updateAfterSaleStatus(afterSaleInfoDO, AfterSaleStatusEnum.REVIEW_PASS.getCode(), AfterSaleStatusEnum.REFUNDING.getCode());
                    return LocalTransactionState.COMMIT_MESSAGE;
                } catch (Exception e) {
                    log.error("system error", e);
                    return LocalTransactionState.ROLLBACK_MESSAGE;
                }
            }

            @Override
            public LocalTransactionState checkLocalTransaction(MessageExt msg) {
                //  ????????????????????????"?????????"
                AfterSaleInfoDO afterSaleInfoDO = afterSaleInfoDAO.getOneByAfterSaleId(afterSaleId);
                if (AfterSaleStatusEnum.REFUNDING.getCode().equals(afterSaleInfoDO.getAfterSaleStatus())) {
                    return LocalTransactionState.COMMIT_MESSAGE;
                }
                return LocalTransactionState.ROLLBACK_MESSAGE;
            }
        });

        //  ???????????????????????????????????????
        String orderId = actualRefundMessage.getOrderId();
        OrderInfoDO orderInfoDO = orderInfoDAO.getByOrderId(orderId);
        ReleaseUserCouponRequest releaseUserCouponRequest = new ReleaseUserCouponRequest();
        releaseUserCouponRequest.setCouponId(orderInfoDO.getCouponId());
        releaseUserCouponRequest.setUserId(orderInfoDO.getUserId());

        return releaseUserCouponRequest;
    }

    /**
     * ???????????????????????????
     * ???????????????????????????????????????????????????????????????????????????????????????????????????,??????????????????????????????????????????
     * <p>
     * ?????????
     * ??????????????????????????????A???????????????10??????????????????B???????????????1?????????????????????????????? ??????????????????A or ??????????????????B???
     * ???????????????????????????A??????????????????A???????????????10????????????
     * ???????????????????????????B??????????????????B???????????????1????????????
     * <p>
     * ????????????????????????A??????3??????????????????A??????2??????????????????A??????5???????????????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public JsonResult<Boolean> processApplyAfterSale(ReturnGoodsOrderRequest returnGoodsOrderRequest) {
        //  ????????????
        checkAfterSaleRequestParam(returnGoodsOrderRequest);
        try {
            //  1????????????????????????
            //  ???order id???sku code????????????id
            String orderId = returnGoodsOrderRequest.getOrderId();
            String skuCode = returnGoodsOrderRequest.getSkuCode();
            /*
                ?????????????????????
                ??????????????????????????????A???????????????????????????????????????????????????????????????????????????????????????orderIdAndSkuCodeList ??????????????????????????????????????????
                ??????????????????????????????A????????????????????????"????????????"?????????????????????????????????????????????
             */
            List<AfterSaleItemDO> orderIdAndSkuCodeList = afterSaleItemDAO.getOrderIdAndSkuCode(orderId, skuCode);
            if (!orderIdAndSkuCodeList.isEmpty()) {
                //  ??????????????????????????????????????????
                Long afterSaleId = orderIdAndSkuCodeList.get(0).getAfterSaleId();
                AfterSaleInfoDO afterSaleInfoDO = afterSaleInfoDAO.getOneByAfterSaleId(afterSaleId);
                if (!AfterSaleStatusEnum.REVOKE.getCode().equals(afterSaleInfoDO.getAfterSaleStatus())) {
                    //  ???"????????????"??????????????????????????????????????????
                    throw new OrderBizException(OrderErrorCodeEnum.PROCESS_APPLY_AFTER_SALE_CANNOT_REPEAT);
                }
            }

            // 2???????????????
            ReturnGoodsAssembleRequest returnGoodsAssembleRequest = buildReturnGoodsData(returnGoodsOrderRequest);

            // 3?????????????????????
            returnGoodsAssembleRequest = calculateReturnGoodsAmount(returnGoodsAssembleRequest);

            TransactionMQProducer producer = defaultProducer.getProducer();
            ReturnGoodsAssembleRequest finalReturnGoodsAssembleRequest = returnGoodsAssembleRequest;

            // 4????????????????????????
            OrderInfoDTO orderInfoDTO = returnGoodsAssembleRequest.getOrderInfoDTO();
            OrderInfoDO orderInfoDO = orderConverter.orderInfoDTO2DO(orderInfoDTO);
            String afterSaleId = orderNoManager.genOrderId(OrderNoTypeEnum.AFTER_SALE.getCode(), orderInfoDO.getUserId());

            producer.setTransactionListener(new TransactionListener() {
                @Override
                public LocalTransactionState executeLocalTransaction(Message message, Object o) {
                    try {
                        // 5?????????????????????
                        insertReturnGoodsAfterSale(finalReturnGoodsAssembleRequest, AfterSaleStatusEnum.COMMITED.getCode(),
                                afterSaleId, orderInfoDO, orderInfoDTO);
                        return LocalTransactionState.COMMIT_MESSAGE;
                    } catch (Exception e) {
                        log.error("system error", e);
                        return LocalTransactionState.ROLLBACK_MESSAGE;
                    }
                }

                @Override
                public LocalTransactionState checkLocalTransaction(MessageExt messageExt) {
                    //  ????????????????????????????????????
                    AfterSaleInfoDO afterSaleInfoDO = afterSaleInfoDAO.getOneByAfterSaleId(Long.valueOf(afterSaleId));
                    List<AfterSaleItemDO> afterSaleItemDOList = afterSaleItemDAO.listByAfterSaleId(Long.valueOf(afterSaleId));
                    List<AfterSaleLogDO> afterSaleLogDOList = afterSaleLogDAO.listByAfterSaleId(Long.valueOf(afterSaleId));
                    List<AfterSaleRefundDO> afterSaleRefundDOList = afterSaleRefundDAO.listByAfterSaleId(Long.valueOf(afterSaleId));
                    if (afterSaleInfoDO != null
                            && !afterSaleItemDOList.isEmpty()
                            && !afterSaleLogDOList.isEmpty()
                            && !afterSaleRefundDOList.isEmpty()) {
                        return LocalTransactionState.COMMIT_MESSAGE;
                    }
                    return LocalTransactionState.ROLLBACK_MESSAGE;
                }
            });

            try {
                // 6???????????????????????????
                CustomerReceiveAfterSaleRequest customerReceiveAfterSaleRequest
                        = orderConverter.convertReturnGoodsAssembleRequest(returnGoodsAssembleRequest);
                customerReceiveAfterSaleRequest.setAfterSaleId(afterSaleId);
                Message message = new Message(RocketMqConstant.AFTER_SALE_CUSTOMER_AUDIT_TOPIC,
                        JSONObject.toJSONString(customerReceiveAfterSaleRequest).getBytes(StandardCharsets.UTF_8));
                // 7?????????????????????
                TransactionSendResult result = producer.sendMessageInTransaction(message, customerReceiveAfterSaleRequest);
                if (!result.getLocalTransactionState().equals(LocalTransactionState.COMMIT_MESSAGE)) {
                    throw new OrderBizException(OrderErrorCodeEnum.SEND_AFTER_SALE_CUSTOMER_AUDIT_MQ_FAILED);
                }
                return JsonResult.buildSuccess(true);
            } catch (Exception e) {
                throw new OrderBizException(OrderErrorCodeEnum.SEND_TRANSACTION_MQ_FAILED);
            }

        } catch (BaseBizException e) {
            log.error("system error", e);
            return JsonResult.buildError(e.getMessage());
        }
    }

    private void checkAfterSaleRequestParam(ReturnGoodsOrderRequest returnGoodsOrderRequest) {
        ParamCheckUtil.checkObjectNonNull(returnGoodsOrderRequest);

        String orderId = returnGoodsOrderRequest.getOrderId();
        ParamCheckUtil.checkStringNonEmpty(orderId, OrderErrorCodeEnum.ORDER_ID_IS_NULL);

        String userId = returnGoodsOrderRequest.getUserId();
        ParamCheckUtil.checkStringNonEmpty(userId, OrderErrorCodeEnum.USER_ID_IS_NULL);

        Integer businessIdentifier = returnGoodsOrderRequest.getBusinessIdentifier();
        ParamCheckUtil.checkObjectNonNull(businessIdentifier, OrderErrorCodeEnum.BUSINESS_IDENTIFIER_IS_NULL);

        Integer returnGoodsCode = returnGoodsOrderRequest.getReturnGoodsCode();
        ParamCheckUtil.checkObjectNonNull(returnGoodsCode, OrderErrorCodeEnum.RETURN_GOODS_CODE_IS_NULL);

        String skuCode = returnGoodsOrderRequest.getSkuCode();
        ParamCheckUtil.checkStringNonEmpty(skuCode, OrderErrorCodeEnum.SKU_IS_NULL);

    }

    private void insertReturnGoodsAfterSale(ReturnGoodsAssembleRequest finalReturnGoodsAssembleRequest, Integer afterSaleStatus,
                                            String afterSaleId, OrderInfoDO orderInfoDO, OrderInfoDTO orderInfoDTO) {
        Integer afterSaleType = finalReturnGoodsAssembleRequest.getAfterSaleType();

        //  ???????????????????????? ?????????????????? ??? ?????????????????? ??????????????????????????????????????????
        AfterSaleInfoDO afterSaleInfoDO = new AfterSaleInfoDO();
        Integer applyRefundAmount = finalReturnGoodsAssembleRequest.getApplyRefundAmount();
        afterSaleInfoDO.setApplyRefundAmount(applyRefundAmount);
        Integer returnGoodAmount = finalReturnGoodsAssembleRequest.getReturnGoodAmount();
        afterSaleInfoDO.setRealRefundAmount(returnGoodAmount);

        //  1????????????????????????
        Integer cancelOrderAfterSaleStatus = AfterSaleStatusEnum.COMMITED.getCode();
        insertReturnGoodsAfterSaleInfoTable(orderInfoDO, afterSaleType, cancelOrderAfterSaleStatus, afterSaleInfoDO, afterSaleId);

        //  2????????????????????????
        insertAfterSaleItemTable(orderInfoDO.getOrderId(), finalReturnGoodsAssembleRequest.getRefundOrderItemDTO(), afterSaleId);

        //  3????????????????????????
        insertReturnGoodsAfterSaleLogTable(afterSaleId, AfterSaleStatusEnum.UN_CREATED.getCode(), afterSaleStatus);

        //  4????????????????????????
        insertAfterSaleRefundTable(orderInfoDTO, afterSaleId, afterSaleInfoDO);
    }

    private ReturnGoodsAssembleRequest buildReturnGoodsData(ReturnGoodsOrderRequest returnGoodsOrderRequest) {
        ReturnGoodsAssembleRequest returnGoodsAssembleRequest = orderConverter.returnGoodRequest2AssembleRequest(returnGoodsOrderRequest);
        String orderId = returnGoodsAssembleRequest.getOrderId();

        //  ?????? ????????????
        OrderInfoDO orderInfoDO = orderInfoDAO.getByOrderId(orderId);
        OrderInfoDTO orderInfoDTO = orderConverter.orderInfoDO2DTO(orderInfoDO);
        returnGoodsAssembleRequest.setOrderInfoDTO(orderInfoDTO);

        //  ?????? ????????????
        List<OrderItemDO> orderItemDOList = orderItemDAO.listByOrderId(orderId);
        List<OrderItemDTO> orderItemDTOList = orderConverter.orderItemDO2DTO(orderItemDOList);
        returnGoodsAssembleRequest.setOrderItemDTOList(orderItemDTOList);

        //  ?????? ??????????????????
        List<AfterSaleItemDO> afterSaleItemDOList = afterSaleItemDAO.listByOrderId(Long.valueOf(orderId));
        List<AfterSaleOrderItemDTO> afterSaleOrderItemRequestList = afterSaleConverter.afterSaleOrderItemDO2DTO(afterSaleItemDOList);
        returnGoodsAssembleRequest.setAfterSaleOrderItemDTOList(afterSaleOrderItemRequestList);

        return returnGoodsAssembleRequest;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JsonResult<Boolean> receivePaymentRefundCallback(RefundCallbackRequest payRefundCallbackRequest) {
        String afterSaleId = payRefundCallbackRequest.getAfterSaleId();
        String key = RedisLockKeyConstants.REFUND_KEY + afterSaleId;
        try {
            boolean lock = redisLock.tryLock(key);
            if (!lock) {
                throw new OrderBizException(OrderErrorCodeEnum.PROCESS_PAY_REFUND_CALLBACK_REPEAT);
            }
            //  1???????????????
            checkRefundCallbackParam(payRefundCallbackRequest);

            //  2??????????????????????????????????????????
            Integer afterSaleStatus;
            Integer refundStatus;
            String refundStatusMsg;
            if (RefundStatusEnum.REFUND_SUCCESS.getCode().equals(payRefundCallbackRequest.getRefundStatus())) {
                afterSaleStatus = AfterSaleStatusEnum.REFUNDED.getCode();
                refundStatus = RefundStatusEnum.REFUND_SUCCESS.getCode();
                refundStatusMsg = RefundStatusEnum.REFUND_SUCCESS.getMsg();
            } else {
                afterSaleStatus = AfterSaleStatusEnum.FAILED.getCode();
                refundStatus = RefundStatusEnum.REFUND_FAIL.getCode();
                refundStatusMsg = RefundStatusEnum.REFUND_FAIL.getMsg();
            }

            //  3????????????????????????????????????????????????????????????
            updatePaymentRefundCallbackAfterSale(payRefundCallbackRequest, afterSaleStatus, refundStatus, refundStatusMsg);

            //  4????????????
            sendRefundMobileMessage(afterSaleId);

            //  5??????APP??????
            sendRefundAppMessage(afterSaleId);

            return JsonResult.buildSuccess();

        } catch (Exception e) {
            log.error(e.getMessage());
            throw new OrderBizException(OrderErrorCodeEnum.PROCESS_PAY_REFUND_CALLBACK_FAILED);
        } finally {
            redisLock.unlock(key);
        }
    }

    private void checkRefundCallbackParam(RefundCallbackRequest payRefundCallbackRequest) {
        ParamCheckUtil.checkObjectNonNull(payRefundCallbackRequest);

        String orderId = payRefundCallbackRequest.getOrderId();
        ParamCheckUtil.checkStringNonEmpty(orderId, OrderErrorCodeEnum.CANCEL_ORDER_ID_IS_NULL);

        String batchNo = payRefundCallbackRequest.getBatchNo();
        ParamCheckUtil.checkStringNonEmpty(batchNo, OrderErrorCodeEnum.PROCESS_PAY_REFUND_CALLBACK_BATCH_NO_IS_NULL);

        Integer refundStatus = payRefundCallbackRequest.getRefundStatus();
        ParamCheckUtil.checkObjectNonNull(refundStatus, OrderErrorCodeEnum.PROCESS_PAY_REFUND_CALLBACK_STATUS_NO_IS_NUL);

        Integer refundFee = payRefundCallbackRequest.getRefundFee();
        ParamCheckUtil.checkObjectNonNull(refundFee, OrderErrorCodeEnum.PROCESS_PAY_REFUND_CALLBACK_FEE_NO_IS_NUL);

        Integer totalFee = payRefundCallbackRequest.getTotalFee();
        ParamCheckUtil.checkObjectNonNull(totalFee, OrderErrorCodeEnum.PROCESS_PAY_REFUND_CALLBACK_TOTAL_FEE_NO_IS_NUL);

        String sign = payRefundCallbackRequest.getSign();
        ParamCheckUtil.checkStringNonEmpty(sign, OrderErrorCodeEnum.PROCESS_PAY_REFUND_CALLBACK_SIGN_NO_IS_NUL);

        String tradeNo = payRefundCallbackRequest.getTradeNo();
        ParamCheckUtil.checkStringNonEmpty(tradeNo, OrderErrorCodeEnum.PROCESS_PAY_REFUND_CALLBACK_TRADE_NO_IS_NUL);

        String afterSaleId = payRefundCallbackRequest.getAfterSaleId();
        ParamCheckUtil.checkStringNonEmpty(afterSaleId, OrderErrorCodeEnum.PROCESS_PAY_REFUND_CALLBACK_AFTER_SALE_ID_IS_NULL);

        Date refundTime = payRefundCallbackRequest.getRefundTime();
        ParamCheckUtil.checkObjectNonNull(refundTime, OrderErrorCodeEnum.PROCESS_PAY_REFUND_CALLBACK_AFTER_SALE_REFUND_TIME_IS_NULL);

        //  ??????????????????????????????????????????????????????????????????????????? or ?????????????????????????????????????????????????????????
        AfterSaleRefundDO afterSaleByDatabase = afterSaleRefundDAO.findAfterSaleRefundByfterSaleId(afterSaleId);
        if (!RefundStatusEnum.UN_REFUND.getCode().equals(afterSaleByDatabase.getRefundStatus())) {
            throw new OrderBizException(OrderErrorCodeEnum.REPEAT_CALLBACK);
        }
    }

    /**
     * ??????????????????????????????????????????
     * ??????????????????????????????????????????????????????????????????????????????????????????
     */
    public ReturnGoodsAssembleRequest calculateReturnGoodsAmount(ReturnGoodsAssembleRequest returnGoodsAssembleRequest) {
        String skuCode = returnGoodsAssembleRequest.getSkuCode();
        String orderId = returnGoodsAssembleRequest.getOrderId();
        //  ???????????????????????????????????????list
        List<OrderItemDTO> refundOrderItemDTOList = Lists.newArrayList();
        List<OrderItemDTO> orderItemDTOList = returnGoodsAssembleRequest.getOrderItemDTOList();
        List<AfterSaleOrderItemDTO> afterSaleOrderItemDTOList = returnGoodsAssembleRequest.getAfterSaleOrderItemDTOList();
        //  ???????????????
        int orderItemNum = orderItemDTOList.size();
        //  ?????????????????????
        int afterSaleOrderItemNum = afterSaleOrderItemDTOList.size();
        //  ?????????????????????????????????????????? ????????????????????????????????????
        if (orderItemNum == 1) {
            OrderItemDTO orderItemDTO = orderItemDTOList.get(0);
            returnGoodsAssembleRequest.setAfterSaleType(AfterSaleTypeEnum.RETURN_MONEY.getCode());
            return calculateWholeOrderFefundAmount(
                    orderId,
                    orderItemDTO.getPayAmount(),
                    orderItemDTO.getOriginAmount(),
                    returnGoodsAssembleRequest
            );
        }
        //  ????????????????????????????????????????????????????????????
        returnGoodsAssembleRequest.setAfterSaleType(AfterSaleTypeEnum.RETURN_GOODS.getCode());
        //  skuCode???orderId???????????????????????????
        OrderItemDO orderItemDO = orderItemDAO.getOrderItemBySkuIdAndOrderId(orderId, skuCode);
        //  ????????????????????? = ?????????????????????????????? + ????????????????????? (????????? 1 ???)
        if (orderItemNum == afterSaleOrderItemNum + 1) {
            //  ????????????????????????????????????
            returnGoodsAssembleRequest = calculateWholeOrderFefundAmount(
                    orderId,
                    orderItemDO.getPayAmount(),
                    orderItemDO.getOriginAmount(),
                    returnGoodsAssembleRequest
            );
        } else {
            //  ??????????????????
            returnGoodsAssembleRequest.setReturnGoodAmount(orderItemDO.getPayAmount());
            returnGoodsAssembleRequest.setApplyRefundAmount(orderItemDO.getOriginAmount());
            returnGoodsAssembleRequest.setLastReturnGoods(false);
        }
        refundOrderItemDTOList.add(orderConverter.orderItemDO2DTO(orderItemDO));
        returnGoodsAssembleRequest.setRefundOrderItemDTO(refundOrderItemDTOList);

        return returnGoodsAssembleRequest;
    }

    private ReturnGoodsAssembleRequest calculateWholeOrderFefundAmount(String orderId, Integer payAmount,
                                                                       Integer originAmount,
                                                                       ReturnGoodsAssembleRequest returnGoodsAssembleRequest) {
        //  ???????????????
        OrderAmountDO deliveryAmount = orderAmountDAO.getOne(orderId, AmountTypeEnum.SHIPPING_AMOUNT.getCode());
        Integer freightAmount = (deliveryAmount == null || deliveryAmount.getAmount() == null) ? 0 : deliveryAmount.getAmount();
        //  ?????????????????? = ?????????????????? + ??????
        Integer returnGoodAmount = payAmount + freightAmount;
        returnGoodsAssembleRequest.setReturnGoodAmount(returnGoodAmount);
        returnGoodsAssembleRequest.setApplyRefundAmount(originAmount);
        returnGoodsAssembleRequest.setAfterSaleType(AfterSaleTypeEnum.RETURN_MONEY.getCode());
        returnGoodsAssembleRequest.setLastReturnGoods(true);

        return returnGoodsAssembleRequest;

    }

    private PayRefundRequest buildPayRefundRequest(ActualRefundMessage actualRefundMessage, AfterSaleRefundDO afterSaleRefundDO) {
        String orderId = actualRefundMessage.getOrderId();
        PayRefundRequest payRefundRequest = new PayRefundRequest();
        payRefundRequest.setOrderId(orderId);
        payRefundRequest.setAfterSaleId(actualRefundMessage.getAfterSaleId());
        payRefundRequest.setRefundAmount(afterSaleRefundDO.getRefundAmount());

        return payRefundRequest;
    }

    private void insertAfterSaleItemTable(String orderId, List<OrderItemDTO> orderItemDTOList, String afterSaleId) {

        List<AfterSaleItemDO> afterSaleItemDOList = Lists.newArrayList();
        for (OrderItemDTO orderItem : orderItemDTOList) {
            AfterSaleItemDO afterSaleItemDO = new AfterSaleItemDO();
            afterSaleItemDO.setAfterSaleId(Long.valueOf(afterSaleId));
            afterSaleItemDO.setOrderId(orderId);
            afterSaleItemDO.setSkuCode(orderItem.getSkuCode());
            afterSaleItemDO.setProductName(orderItem.getProductName());
            afterSaleItemDO.setProductImg(orderItem.getProductImg());
            afterSaleItemDO.setReturnQuantity(orderItem.getSaleQuantity());
            afterSaleItemDO.setOriginAmount(orderItem.getOriginAmount());
            afterSaleItemDO.setApplyRefundAmount(orderItem.getOriginAmount());
            afterSaleItemDO.setRealRefundAmount(orderItem.getPayAmount());

            afterSaleItemDOList.add(afterSaleItemDO);
        }
        afterSaleItemDAO.saveBatch(afterSaleItemDOList);
    }

    private AfterSaleRefundDO insertAfterSaleRefundTable(OrderInfoDTO orderInfoDTO, String afterSaleId, AfterSaleInfoDO afterSaleInfoDO) {
        String orderId = orderInfoDTO.getOrderId();
        OrderPaymentDetailDO paymentDetail = orderPaymentDetailDAO.getPaymentDetailByOrderId(orderId);

        AfterSaleRefundDO afterSaleRefundDO = new AfterSaleRefundDO();
        afterSaleRefundDO.setAfterSaleId(afterSaleId);
        afterSaleRefundDO.setOrderId(orderId);
        afterSaleRefundDO.setAccountType(AccountTypeEnum.THIRD.getCode());
        afterSaleRefundDO.setRefundStatus(RefundStatusEnum.UN_REFUND.getCode());
        afterSaleRefundDO.setRemark(RefundStatusEnum.UN_REFUND.getMsg());
        afterSaleRefundDO.setRefundAmount(afterSaleInfoDO.getRealRefundAmount());
        afterSaleRefundDO.setAfterSaleBatchNo(orderId + RandomUtil.genRandomNumber(10));

        if (paymentDetail != null) {
            afterSaleRefundDO.setOutTradeNo(paymentDetail.getOutTradeNo());
            afterSaleRefundDO.setPayType(paymentDetail.getPayType());
        }
        afterSaleRefundDAO.save(afterSaleRefundDO);
        log.info("????????????????????????,?????????:{},????????????:{},??????:{}", orderId, afterSaleId, afterSaleRefundDO.getRefundStatus());
        return afterSaleRefundDO;
    }


    @Transactional(rollbackFor = Exception.class)
    @Override
    public void revokeAfterSale(RevokeAfterSaleRequest request) {

        //1??????????????????
        Long afterSaleId = request.getAfterSaleId();
        AfterSaleInfoDO afterSaleInfo = afterSaleInfoDAO.getOneByAfterSaleId(afterSaleId);
        ParamCheckUtil.checkObjectNonNull(afterSaleInfo, OrderErrorCodeEnum.AFTER_SALE_ID_IS_NULL);

        //2??????????????????????????????????????????????????????????????????????????????
        if (!AfterSaleStatusEnum.COMMITED.getCode().equals(afterSaleInfo.getAfterSaleStatus())) {
            throw new OrderBizException(OrderErrorCodeEnum.AFTER_SALE_CANNOT_REVOKE);
        }

        //3??????????????????????????????"?????????"
        afterSaleInfoDAO.updateStatus(afterSaleInfo.getAfterSaleId(), AfterSaleStatusEnum.COMMITED.getCode(),
                AfterSaleStatusEnum.REVOKE.getCode());

        //4????????????????????????????????????
        afterSaleLogDAO.save(afterSaleOperateLogFactory.get(afterSaleInfo, AfterSaleStatusChangeEnum.AFTER_SALE_REVOKE));

    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void receiveCustomerAuditReject(CustomerAuditAssembleRequest customerAuditAssembleResult) {
        AfterSaleInfoDO afterSaleInfoDO = afterSaleInfoDAO.getOneByAfterSaleId(customerAuditAssembleResult.getAfterSaleId());
        //  ?????????????????????????????????????????????
        if (afterSaleInfoDO.getAfterSaleStatus() > AfterSaleStatusEnum.COMMITED.getCode()) {
            throw new OrderBizException(OrderErrorCodeEnum.CUSTOMER_AUDIT_CANNOT_REPEAT);
        }

        //  ??????????????????
        customerAuditAssembleResult.setReviewReason(CustomerAuditResult.REJECT.getMsg());
        afterSaleInfoDAO.updateCustomerAuditAfterSaleResult(AfterSaleStatusEnum.REVIEW_REJECTED.getCode(), customerAuditAssembleResult);

        //  ??????????????????
        AfterSaleLogDO afterSaleLogDO = afterSaleOperateLogFactory.get(afterSaleInfoDO, AfterSaleStatusChangeEnum.AFTER_SALE_CUSTOMER_AUDIT_REJECT);
        afterSaleLogDAO.save(afterSaleLogDO);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void receiveCustomerAuditAccept(CustomerAuditAssembleRequest customerAuditAssembleResult) {
        AfterSaleInfoDO afterSaleInfoDO = afterSaleInfoDAO.getOneByAfterSaleId(customerAuditAssembleResult.getAfterSaleId());
        //  ?????????????????????????????????????????????
        if (afterSaleInfoDO.getAfterSaleStatus() > AfterSaleStatusEnum.COMMITED.getCode()) {
            throw new OrderBizException(OrderErrorCodeEnum.CUSTOMER_AUDIT_CANNOT_REPEAT);
        }

        //  ??????????????????
        customerAuditAssembleResult.setReviewReason(CustomerAuditResult.ACCEPT.getMsg());
        afterSaleInfoDAO.updateCustomerAuditAfterSaleResult(AfterSaleStatusEnum.REVIEW_PASS.getCode(), customerAuditAssembleResult);

        //  ??????????????????
        AfterSaleLogDO afterSaleLogDO = afterSaleOperateLogFactory.get(afterSaleInfoDO, AfterSaleStatusChangeEnum.AFTER_SALE_CUSTOMER_AUDIT_PASS);
        afterSaleLogDAO.save(afterSaleLogDO);

    }

    @Override
    public Integer findCustomerAuditAfterSaleStatus(Long afterSaleId) {
        AfterSaleInfoDO afterSaleInfoDO = afterSaleInfoDAO.getOneByAfterSaleId(afterSaleId);
        return afterSaleInfoDO.getAfterSaleStatus();
    }
}
