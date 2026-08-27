# Regression Metrics

## When to use
- Evaluating models that predict continuous numerical values
- Selecting between models with different error profiles
- Communicating prediction quality in domain-meaningful units

## Pattern

### Mean Squared Error (MSE)
- Average of squared differences between predicted and actual values
- Penalizes large errors quadratically; sensitive to outliers
- Use when large errors are disproportionately costly (structural engineering, safety-critical)
- Units are squared (e.g., dollars-squared), which limits interpretability

### Root Mean Squared Error (RMSE)
- Square root of MSE; same units as the target variable
- Most commonly reported metric for regression
- Still outlier-sensitive but human-readable
- Compare RMSE to the standard deviation of the target: RMSE << std means the model captures significant variance

### Mean Absolute Error (MAE)
- Average of absolute differences between predicted and actual values
- Robust to outliers compared to MSE/RMSE
- Use when all errors should be weighted equally regardless of magnitude
- Corresponds to the median as the optimal constant predictor (MSE corresponds to the mean)

### Mean Absolute Percentage Error (MAPE)
- MAE expressed as a percentage of actual values
- Intuitive for business stakeholders ("off by X% on average")
- Undefined when actual values are zero; heavily skewed when actuals are near zero
- Asymmetric: penalizes over-predictions less than under-predictions
- Consider symmetric MAPE (sMAPE) or weighted MAPE for alternatives

### R-squared (Coefficient of Determination)
- Proportion of variance explained by the model (1 - SS_res / SS_tot)
- 1.0 = perfect fit, 0.0 = no better than predicting the mean
- Can be negative if the model is worse than the mean predictor
- Use adjusted R-squared when comparing models with different feature counts
- Domain-dependent: R-squared of 0.7 may be excellent in social science, poor in physics

### Choosing the Right Metric
- Outlier-sensitive context, large errors costly: MSE / RMSE
- Robust, equal-weight errors: MAE
- Stakeholder communication, percentage intuition: MAPE (if no near-zero actuals)
- Model explanatory power, feature selection: R-squared / adjusted R-squared
- Always report at least two metrics (e.g., RMSE + MAE) to reveal error distribution shape
- If RMSE >> MAE, the error distribution has heavy tails (a few large errors dominate)

### Residual Analysis
- Plot residuals vs predicted values; look for patterns (heteroscedasticity, non-linearity)
- Plot residual distribution; should be roughly normal and centered at zero
- Q-Q plots reveal departures from normality in residuals

## Gotchas / Anti-patterns
- Reporting only R-squared without an error metric in original units
- Using MAPE when target values include zeros or near-zero values
- Comparing MSE/RMSE across datasets with different scales (use normalized variants)
- Ignoring residual plots; a good aggregate metric can mask systematic bias
- Optimizing MSE when the real cost function is asymmetric (e.g., under-forecasting inventory is worse than over-forecasting)
- Using R-squared to compare models across different datasets or target variables

## References
- Hyndman & Koehler, "Another look at measures of forecast accuracy" (2006)
- Botchkarev, "A New Typology Design of Performance Metrics" (2019)
- NIST Engineering Statistics Handbook: Residual Analysis
