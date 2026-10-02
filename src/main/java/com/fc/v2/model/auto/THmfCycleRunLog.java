package com.fc.v2.model.auto;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.Date;

/**
 * 结息催交轮次台账对象 t_hmf_cycle_run_log
 *
 * 一轮扫描一行主行、挑中的单各挂明细：挑中哪些、按住/办结/催不成各一条，只添不抹。
 * 页面三个数只认本表本 run_no 的明细，一条一条点得出来；看屏与点档同一次算。
 *
 * @author fuce
 * @date 2026-10-02
 */
@TableName("t_hmf_cycle_run_log")
@ApiModel(value = "THmfCycleRunLog", description = "结息催交轮次台账")
public class THmfCycleRunLog implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 轮次号（一轮一号，顺序自占） */
    @TableField("run_no")
    @ApiModelProperty(value = "轮次号")
    private Integer runNo;

    /** 本轮扫描所照的止点时刻（页面时刻与它不齐时以它为准） */
    @TableField("run_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "本轮扫描所照的止点时刻")
    private Date runAt;

    /** 结息催交单id */
    @TableField("item_id")
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "结息催交单id")
    private Long itemId;

    /** 结息催交单号（誊照，便于离主行点档） */
    @TableField("item_no")
    @ApiModelProperty(value = "结息催交单号")
    private String itemNo;

    /** 本轮该条的落定 0候办按住 1已办结 2催不成 */
    @TableField("action")
    @ApiModelProperty(value = "本轮落定 0候办按住 1已办结 2催不成")
    private Integer action;

    /** 办结那一刻（action=1 才有，与主单 finish_at 同源誊一笔） */
    @TableField("finish_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "办结那一刻")
    private Date finishAt;

    /** 按住缘由/催不成缘由/撤回笔迹 */
    @TableField("detail")
    @ApiModelProperty(value = "缘由笔迹")
    private String detail;

    /** 删除标记 0正常 1删除 */
    @TableField("del_flag")
    @ApiModelProperty(value = "删除标记 0正常 1删除")
    private Integer delFlag;

    /** 创建者 */
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    @ApiModelProperty(value = "创建者")
    private String createBy;

    /** 创建时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    /** 更新者 */
    @TableField(value = "update_by", fill = FieldFill.UPDATE)
    @ApiModelProperty(value = "更新者")
    private String updateBy;

    /** 更新时间 */
    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "更新时间")
    private Date updateTime;

    /** 备注 */
    @TableField("remark")
    @ApiModelProperty(value = "备注")
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getRunNo() {
        return runNo;
    }

    public void setRunNo(Integer runNo) {
        this.runNo = runNo;
    }

    public Date getRunAt() {
        return runAt;
    }

    public void setRunAt(Date runAt) {
        this.runAt = runAt;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public String getItemNo() {
        return itemNo;
    }

    public void setItemNo(String itemNo) {
        this.itemNo = itemNo;
    }

    public Integer getAction() {
        return action;
    }

    public void setAction(Integer action) {
        this.action = action;
    }

    public Date getFinishAt() {
        return finishAt;
    }

    public void setFinishAt(Date finishAt) {
        this.finishAt = finishAt;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Integer getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(Integer delFlag) {
        this.delFlag = delFlag;
    }

    public String getCreateBy() {
        return createBy;
    }

    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public String getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(String updateBy) {
        this.updateBy = updateBy;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
