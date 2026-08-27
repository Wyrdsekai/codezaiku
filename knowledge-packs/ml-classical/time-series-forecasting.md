# Time Series Forecasting

## When to use
- Predicting future values of a temporally ordered sequence
- Demand forecasting, capacity planning, financial projections
- Detecting trends, seasonality, and cyclical patterns
- Anomaly detection on temporal data via forecast residuals

## Pattern

### ARIMA (AutoRegressive Integrated Moving Average)
- Components: AR(p) autoregressive terms, I(d) differencing order, MA(q) moving average terms
- Use ACF/PACF plots to identify p and q: PACF cuts off at lag p (AR), ACF cuts off at lag q (MA)
- Differencing (d): apply until the series is stationary. Use ADF (Augmented Dickey-Fuller) test to verify stationarity.
- Seasonal ARIMA (SARIMA): adds (P, D, Q, m) for seasonal components where m is the seasonal period
- Auto-ARIMA (e.g., `pmdarima.auto_arima`) performs stepwise search over (p,d,q)(P,D,Q,m) using AIC/BIC
- ARIMA assumes linear relationships and stationary residuals; check residual diagnostics (Ljung-Box test)

### Exponential Smoothing
- **Simple (SES)**: level only, no trend or seasonality. For short-term forecasts of stable series.
- **Holt's linear**: level + trend. Use damped trend (`damped_trend=True`) unless you have strong evidence of sustained linear growth.
- **Holt-Winters**: level + trend + seasonality. Additive seasonality for constant amplitude, multiplicative for amplitude proportional to level.
- ETS (Error-Trend-Seasonal) framework formalizes exponential smoothing as state-space models with automatic model selection
- Fast, interpretable, and competitive with complex methods on many datasets
- Best for univariate series with clear trend/seasonality patterns

### Prophet
- Designed for business time series with daily/weekly/yearly seasonality and holiday effects
- Handles missing data and outliers gracefully — treats them as missing
- Add custom seasonalities via `add_seasonality()` for domain-specific periods
- Changepoint detection: `changepoint_prior_scale` controls flexibility. Increase (0.5-1.0) for volatile series, decrease (0.01-0.05) for stable series.
- `add_regressor()` for external regressors (weather, promotions, etc.)
- Saturating growth: use `growth='logistic'` with floor and cap for bounded forecasts
- Best for daily data with 1-2 years of history; less suitable for sub-hourly or very short series

### Neural Approaches
- **N-BEATS**: pure deep learning, no time-series-specific assumptions, interpretable decomposition into trend and seasonality stacks
- **N-HiTS**: hierarchical interpolation, more efficient than N-BEATS for long horizons
- **Temporal Fusion Transformer (TFT)**: handles static covariates, known future inputs, and unknown future inputs with attention-based interpretability
- **DeepAR**: probabilistic forecasting with autoregressive RNN, produces prediction intervals
- Neural methods need more data (>1000 time steps) and longer training; they underperform classical methods on small datasets
- Use transfer learning / pre-trained foundation models (TimesFM, Chronos) for limited data scenarios

### Seasonality
- Test for seasonality: look at ACF at seasonal lags, seasonal decomposition (STL), periodogram
- STL decomposition: separates trend, seasonal, and residual components. Use `robust=True` for outlier resilience.
- Multiple seasonalities (e.g., daily + weekly + yearly): Prophet handles natively; ARIMA needs TBATS or manual feature engineering
- Fourier terms as features: add sin/cos pairs at seasonal frequencies for flexible seasonal modeling in any regression framework

### Forecast Evaluation
- Always use time-respecting splits: train on past, test on future. Never shuffle time series data.
- Walk-forward validation (expanding window) is the gold standard
- Metrics: MAPE (scale-independent but undefined at zero), RMSE (penalizes large errors), MAE (robust), MASE (scaled, handles zeros)
- Compare against naive baselines: seasonal naive (repeat last season) and drift (linear extrapolation)
- Prediction intervals: calibrate with conformal prediction or quantile regression if model does not produce native intervals

## Gotchas / Anti-patterns
- Shuffling time series data in cross-validation leaks future information into training
- Using MAPE when the series contains zeros or near-zero values — it explodes to infinity
- Fitting ARIMA to non-stationary data without differencing: the model will appear to fit well but forecast poorly
- Prophet defaults assume daily data; using it on hourly data without adjusting seasonality periods produces wrong results
- Overfitting neural forecasters on short series (<500 points): classical methods will outperform
- Ignoring calendar effects (holidays, weekends, business days) when they clearly affect the series
- Forecasting very far ahead without widening prediction intervals — point forecasts degrade quickly, convey false confidence
- Treating each time series independently when you have a hierarchy (store > region > national): use hierarchical reconciliation

## References
- Hyndman & Athanasopoulos, "Forecasting: Principles and Practice" (3rd edition, online: otexts.com/fpp3)
- Taylor & Letham (2018), "Forecasting at Scale" (Prophet)
- Oreshkin et al. (2020), "N-BEATS: Neural Basis Expansion Analysis for Time Series Forecasting"
- Lim et al. (2021), "Temporal Fusion Transformers for Interpretable Multi-horizon Time Series Forecasting"
