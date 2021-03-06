package com.ruyuan.eshop.inventory.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.ruyuan.eshop.common.constants.CoreConstant;
import com.ruyuan.eshop.common.constants.RedisLockKeyConstants;
import com.ruyuan.eshop.common.redis.RedisCache;
import com.ruyuan.eshop.common.redis.RedisLock;
import com.ruyuan.eshop.common.utils.LoggerFormat;
import com.ruyuan.eshop.common.utils.ParamCheckUtil;
import com.ruyuan.eshop.inventory.cache.CacheSupport;
import com.ruyuan.eshop.inventory.dao.ProductStockDAO;
import com.ruyuan.eshop.inventory.dao.ProductStockLogDAO;
import com.ruyuan.eshop.inventory.domain.dto.DeductStockDTO;
import com.ruyuan.eshop.inventory.domain.entity.ProductStockDO;
import com.ruyuan.eshop.inventory.domain.entity.ProductStockLogDO;
import com.ruyuan.eshop.inventory.domain.request.*;
import com.ruyuan.eshop.inventory.enums.StockLogStatusEnum;
import com.ruyuan.eshop.inventory.exception.InventoryBizException;
import com.ruyuan.eshop.inventory.exception.InventoryErrorCodeEnum;
import com.ruyuan.eshop.inventory.service.InventoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.MessageFormat;
import java.util.List;
import java.util.Map;

/**
 * @author zhonghuashishan
 * @version 1.0
 */
@Service
@Slf4j
public class InventoryServiceImpl implements InventoryService {

    @Autowired
    private ProductStockDAO productStockDAO;

    @Autowired
    private RedisLock redisLock;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private AddProductStockProcessor addProductStockProcessor;

    @Autowired
    private ModifyProductStockProcessor modifyProductStockProcessor;

    @Autowired
    private ProductStockLogDAO productStockLogDAO;

    @Autowired
    private DeductProductStockProcessor deductProductStockProcessor;

    @Autowired
    private ReleaseProductStockProcessor releaseProductStockProcessor;

    @Autowired
    private SyncStockToCacheProcessor syncStockToCacheProcessor;

    /**
     * ??????????????????
     *
     * @param deductProductStockRequest
     * @return
     */
    @Override
    public Boolean deductProductStock(DeductProductStockRequest deductProductStockRequest) {
        // ????????????
        checkLockProductStockRequest(deductProductStockRequest);
        String orderId = deductProductStockRequest.getOrderId();
        List<DeductProductStockRequest.OrderItemRequest> orderItemRequestList =
                deductProductStockRequest.getOrderItemRequestList();
        for (DeductProductStockRequest.OrderItemRequest orderItemRequest : orderItemRequestList) {
            String skuCode = orderItemRequest.getSkuCode();
            //1?????????mysql????????????
            ProductStockDO productStockDO = productStockDAO.getBySkuCode(skuCode);
            log.info(LoggerFormat.build()
                    .remark("??????mysql????????????")
                    .data("productStockDO", productStockDO)
                    .finish());
            if (productStockDO == null) {
                log.error(LoggerFormat.build()
                        .remark("???????????????????????????")
                        .data("skuCode", skuCode)
                        .finish());
                throw new InventoryBizException(InventoryErrorCodeEnum.PRODUCT_SKU_STOCK_NOT_FOUND_ERROR);
            }

            //2?????????redis????????????
            String productStockKey = CacheSupport.buildProductStockKey(skuCode);
            Map<String, String> productStockValue = redisCache.hGetAll(productStockKey);
            if (productStockValue.isEmpty()) {
                // ??????????????????redis??????????????????mysql??????????????????redis??????mysql???????????????
                addProductStockProcessor.addStockToRedis(productStockDO);
            }

            //3?????????redis???????????????????????????????????????
            String lockKey = MessageFormat.format(RedisLockKeyConstants.ORDER_DEDUCT_PRODUCT_STOCK_KEY, orderId, skuCode);
            Boolean locked = redisLock.tryLock(lockKey);
            if (!locked) {
                log.error(LoggerFormat.build()
                        .remark("???????????????????????????")
                        .data("orderId", orderId)
                        .data("skuCode", skuCode)
                        .finish());
                throw new InventoryBizException(InventoryErrorCodeEnum.DEDUCT_PRODUCT_SKU_STOCK_CANNOT_ACQUIRE);
            }
            try {
                //4???????????????????????????
                ProductStockLogDO productStockLog = productStockLogDAO.getLog(orderId, skuCode);
                if (null != productStockLog) {
                    log.info("??????????????????????????????????????????,orderId={},skuCode={}", orderId, skuCode);
                    return true;
                }

                Integer saleQuantity = orderItemRequest.getSaleQuantity();
                Integer originSaleStock = productStockDO.getSaleStockQuantity().intValue();
                Integer originSaledStock = productStockDO.getSaledStockQuantity().intValue();

                //5????????????????????????
                DeductStockDTO deductStock = new DeductStockDTO(orderId, skuCode, saleQuantity, originSaleStock, originSaledStock);
                deductProductStockProcessor.doDeduct(deductStock);
            } finally {
                redisLock.unlock(lockKey);
            }
        }
        log.info(LoggerFormat.build()
                .remark("deductProductStock->response")
                .finish());
        return true;
    }


    /**
     * ??????????????????????????????
     *
     * @param deductProductStockRequest
     */
    private void checkLockProductStockRequest(DeductProductStockRequest deductProductStockRequest) {
        String orderId = deductProductStockRequest.getOrderId();
        ParamCheckUtil.checkStringNonEmpty(orderId);
        List<DeductProductStockRequest.OrderItemRequest> orderItemRequestList =
                deductProductStockRequest.getOrderItemRequestList();
        ParamCheckUtil.checkCollectionNonEmpty(orderItemRequestList);
    }

    /**
     * ??????????????????
     */
    @Override
    public Boolean releaseProductStock(ReleaseProductStockRequest releaseProductStockRequest) {
        // ????????????
        checkReleaseProductStockRequest(releaseProductStockRequest);
        String orderId = releaseProductStockRequest.getOrderId();
        List<ReleaseProductStockRequest.OrderItemRequest> orderItemRequestList =
                releaseProductStockRequest.getOrderItemRequestList();
        for (ReleaseProductStockRequest.OrderItemRequest orderItemRequest : orderItemRequestList) {
            String skuCode = orderItemRequest.getSkuCode();

            //1?????????redis????????????????????????
            // (1)??????????????????????????????
            // (2)?????????????????????mysql+redis?????????????????????????????????????????????????????????????????????
            //    ???????????????+?????????
            String lockKey = RedisLockKeyConstants.RELEASE_PRODUCT_STOCK_KEY + skuCode;
            // ????????????????????????3s
            Boolean locked = redisLock.tryLock(lockKey, CoreConstant.DEFAULT_WAIT_SECONDS);
            if (!locked) {
                log.error("???????????????????????????,orderId={},skuCode={}", orderId, skuCode);
                throw new InventoryBizException(InventoryErrorCodeEnum.RELEASE_PRODUCT_SKU_STOCK_LOCK_CANNOT_ACQUIRE);
            }

            try {
                //2?????????mysql????????????
                ProductStockDO productStockDO = productStockDAO.getBySkuCode(skuCode);
                if (productStockDO == null) {
                    throw new InventoryBizException(InventoryErrorCodeEnum.PRODUCT_SKU_STOCK_NOT_FOUND_ERROR);
                }

                //3?????????redis????????????
                String productStockKey = CacheSupport.buildProductStockKey(skuCode);
                Map<String, String> productStockValue = redisCache.hGetAll(productStockKey);
                if (productStockValue.isEmpty()) {
                    // ??????????????????redis??????????????????mysql??????????????????redis??????mysql???????????????
                    addProductStockProcessor.addStockToRedis(productStockDO);
                }

                Integer saleQuantity = orderItemRequest.getSaleQuantity();

                //4??????????????????????????????
                ProductStockLogDO productStockLog = productStockLogDAO.getLog(orderId, skuCode);
                if (null != productStockLog && productStockLog.getStatus().equals(StockLogStatusEnum.RELEASED.getCode())) {
                    log.info("??????????????????,orderId={},skuCode={}", orderId, skuCode);
                    return true;
                }

                //5???????????????
                releaseProductStockProcessor.doRelease(orderId, skuCode, saleQuantity, productStockLog);

            } finally {
                redisLock.unlock(lockKey);
            }
        }
        return true;
    }

    @Override
    public Boolean addProductStock(AddProductStockRequest request) {
        log.info("?????????????????????request={}", JSONObject.toJSONString(request));
        //1???????????????
        checkAddProductStockRequest(request);

        //2?????????????????????
        ProductStockDO productStock = productStockDAO.getBySkuCode(request.getSkuCode());
        ParamCheckUtil.checkObjectNull(productStock, InventoryErrorCodeEnum.PRODUCT_SKU_STOCK_EXISTED_ERROR);

        //3?????????redis???????????????
        String lockKey = RedisLockKeyConstants.ADD_PRODUCT_STOCK_KEY + request.getSkuCode();
        Boolean locked = redisLock.tryLock(lockKey);
        if (!locked) {
            throw new InventoryBizException(InventoryErrorCodeEnum.ADD_PRODUCT_SKU_STOCK_ERROR);
        }
        try {
            //4?????????????????????????????????
            addProductStockProcessor.doAdd(request);
        } finally {
            //5?????????
            redisLock.unlock(lockKey);
        }

        return true;
    }


    @Override
    public Boolean modifyProductStock(ModifyProductStockRequest request) {
        log.info("?????????????????????request={}", JSONObject.toJSONString(request));

        //1???????????????
        checkModifyProductStockRequest(request);

        //2?????????????????????
        ProductStockDO productStock = productStockDAO.getBySkuCode(request.getSkuCode());
        ParamCheckUtil.checkObjectNonNull(productStock, InventoryErrorCodeEnum.PRODUCT_SKU_STOCK_NOT_FOUND_ERROR);

        //3???????????????????????????????????????0
        Long stockIncremental = request.getStockIncremental();
        Long saleStockQuantity = productStock.getSaleStockQuantity();
        if (saleStockQuantity + stockIncremental < 0) {
            throw new InventoryBizException(InventoryErrorCodeEnum.SALE_STOCK_QUANTITY_CANNOT_BE_NEGATIVE_NUMBER);
        }

        //4???????????????????????????mysql???redis??????????????????
        String lockKey = RedisLockKeyConstants.MODIFY_PRODUCT_STOCK_KEY + request.getSkuCode();
        Boolean locked = redisLock.tryLock(lockKey);
        if (!locked) {
            throw new InventoryBizException(InventoryErrorCodeEnum.MODIFY_PRODUCT_SKU_STOCK_ERROR);
        }

        try {
            modifyProductStockProcessor.doModify(productStock, stockIncremental);
        } finally {
            redisLock.unlock(lockKey);
        }

        return true;
    }

    @Override
    public Boolean syncStockToCache(SyncStockToCacheRequest request) {
        //1???????????????
        ParamCheckUtil.checkStringNonEmpty(request.getSkuCode(), InventoryErrorCodeEnum.SKU_CODE_IS_EMPTY);

        //2?????????
        syncStockToCacheProcessor.doSync(request.getSkuCode());

        return true;
    }

    /**
     * ??????????????????????????????
     *
     * @param releaseProductStockRequest
     */
    private void checkReleaseProductStockRequest(ReleaseProductStockRequest releaseProductStockRequest) {
        String orderId = releaseProductStockRequest.getOrderId();
        ParamCheckUtil.checkStringNonEmpty(orderId);

        List<ReleaseProductStockRequest.OrderItemRequest> orderItemRequestList =
                releaseProductStockRequest.getOrderItemRequestList();
        ParamCheckUtil.checkCollectionNonEmpty(orderItemRequestList);
    }

    /**
     * ??????????????????????????????
     *
     * @param request
     */
    private void checkAddProductStockRequest(AddProductStockRequest request) {
        ParamCheckUtil.checkStringNonEmpty(request.getSkuCode(), InventoryErrorCodeEnum.SKU_CODE_IS_EMPTY);
        ParamCheckUtil.checkObjectNonNull(request.getSaleStockQuantity(), InventoryErrorCodeEnum.SALE_STOCK_QUANTITY_IS_EMPTY);
        ParamCheckUtil.checkLongMin(request.getSaleStockQuantity(), 0L, InventoryErrorCodeEnum.SALE_STOCK_QUANTITY_CANNOT_BE_NEGATIVE_NUMBER);
    }

    /**
     * ??????????????????????????????
     *
     * @param request
     */
    private void checkModifyProductStockRequest(ModifyProductStockRequest request) {
        ParamCheckUtil.checkStringNonEmpty(request.getSkuCode(), InventoryErrorCodeEnum.SKU_CODE_IS_EMPTY);
        ParamCheckUtil.checkObjectNonNull(request.getStockIncremental(), InventoryErrorCodeEnum.SALE_STOCK_INCREMENTAL_QUANTITY_IS_EMPTY);
        if (0L == request.getStockIncremental()) {
            throw new InventoryBizException(InventoryErrorCodeEnum.SALE_STOCK_INCREMENTAL_QUANTITY_CANNOT_BE_ZERO);
        }
    }

}
