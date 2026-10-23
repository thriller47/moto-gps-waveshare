#pragma once

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

bool ota_manager_start_ap_server(void);
void ota_manager_stop_ap_server(void);
bool ota_manager_start(void);
int ota_manager_get_progress(void);
bool ota_manager_is_active(void);
bool ota_manager_has_failed(void);

#ifdef __cplusplus
}
#endif
